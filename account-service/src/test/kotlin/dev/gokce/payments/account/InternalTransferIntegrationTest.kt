package dev.gokce.payments.account

import com.fasterxml.jackson.databind.JsonNode
import dev.gokce.payments.account.domain.Direction
import dev.gokce.payments.account.infrastructure.persistence.AccountRepository
import dev.gokce.payments.account.infrastructure.persistence.LedgerEntryRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class InternalTransferIntegrationTest @Autowired constructor(
    private val accounts: AccountRepository,
    private val ledgerEntries: LedgerEntryRepository,
) : PostgresTestBase() {

    @Test
    fun `a transfer debits the source, credits the destination and balances to zero`() {
        val source = fundedAccount("Alan Turing", 30_000)
        val destination = createAccount("Edsger Dijkstra")
        val transferId = UUID.randomUUID()

        val response = transfer(transferId, source, destination, amount = 12_500)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = response.body!!
        assertThat(body["replayed"].asBoolean()).isFalse()
        assertThat(body["amount"].asLong()).isEqualTo(12_500)

        assertThat(balanceOf(source)).isEqualTo(17_500)
        assertThat(balanceOf(destination)).isEqualTo(12_500)

        val entries = ledgerEntries.findByTransferId(transferId)
        assertThat(entries).hasSize(2)
        assertThat(entries.single { it.direction == Direction.DEBIT }.accountId).isEqualTo(source)
        assertThat(entries.single { it.direction == Direction.CREDIT }.accountId).isEqualTo(destination)
        assertThat(entries.sumOf { if (it.direction == Direction.CREDIT) it.amount else -it.amount })
            .`as`("the two sides of a movement must cancel out")
            .isZero()
    }

    @Test
    fun `the same transferId twice moves the money once`() {
        val source = fundedAccount("Barbara Liskov", 10_000)
        val destination = createAccount("Leslie Lamport")
        val transferId = UUID.randomUUID()

        val first = transfer(transferId, source, destination, amount = 4_000)
        val second = transfer(transferId, source, destination, amount = 4_000)

        assertThat(first.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(second.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(first.body!!["replayed"].asBoolean()).isFalse()
        assertThat(second.body!!["replayed"].asBoolean())
            .`as`("the second call must be recognised as a replay")
            .isTrue()
        assertThat(second.body!!["postedAt"].asText())
            .`as`("a replay answers with the original posting time, not the time of the retry")
            .isEqualTo(first.body!!["postedAt"].asText())

        assertThat(balanceOf(source))
            .`as`("money must have moved exactly once")
            .isEqualTo(6_000)
        assertThat(balanceOf(destination)).isEqualTo(4_000)
        assertThat(ledgerEntries.findByTransferId(transferId))
            .`as`("a replay writes no further entries")
            .hasSize(2)
    }

    @Test
    fun `reusing a transferId with different details is refused`() {
        val source = fundedAccount("Niklaus Wirth", 10_000)
        val destination = createAccount("Tony Hoare")
        val transferId = UUID.randomUUID()

        transfer(transferId, source, destination, amount = 1_000)
        val conflicting = transfer(transferId, source, destination, amount = 9_999)

        assertThat(conflicting.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
        assertThat(conflicting.body!!["title"].asText()).isEqualTo("Transfer conflict")
        assertThat(balanceOf(source))
            .`as`("the conflicting amount must not be posted")
            .isEqualTo(9_000)
    }

    @Test
    fun `50 concurrent transfers never drive the balance below zero`() {
        val transfers = 50
        val amount = 1_000L
        val funded = 20_000L
        val affordable = (funded / amount).toInt() // exactly 20 of the 50 can be paid

        val source = fundedAccount("Grace Hopper", funded)
        val destination = createAccount("Margaret Hamilton")

        val pool = Executors.newFixedThreadPool(transfers)
        val startGate = CountDownLatch(1)
        val results = try {
            val futures = (1..transfers).map {
                pool.submit<HttpStatus> {
                    startGate.await() // fire all of them at once, for maximum contention
                    transfer(UUID.randomUUID(), source, destination, amount).statusCode as HttpStatus
                }
            }
            startGate.countDown()
            futures.map { it.get(120, TimeUnit.SECONDS) }
        } finally {
            pool.shutdown()
            pool.awaitTermination(30, TimeUnit.SECONDS)
        }

        assertThat(results.filter { it != HttpStatus.OK && it != HttpStatus.UNPROCESSABLE_ENTITY })
            .`as`("no request may fail for any reason other than insufficient funds")
            .isEmpty()
        assertThat(results.count { it == HttpStatus.OK })
            .`as`("exactly as many transfers as the balance could pay for")
            .isEqualTo(affordable)
        assertThat(results.count { it == HttpStatus.UNPROCESSABLE_ENTITY })
            .isEqualTo(transfers - affordable)

        assertThat(balanceOf(source))
            .`as`("the source is emptied, never overdrawn")
            .isZero()
        assertThat(balanceOf(destination)).isEqualTo(funded)

        val sourceEntries = ledgerEntries.findByAccountId(source, Pageable.unpaged()).content
        assertThat(sourceEntries.count { it.direction == Direction.DEBIT })
            .`as`("one debit per successful transfer, and nothing from the rejected ones")
            .isEqualTo(affordable)
        assertThat(sourceEntries.count { it.direction == Direction.CREDIT })
            .`as`("the only credit on the source is the deposit that funded it")
            .isEqualTo(1)

        // The cache cannot have drifted from the ledger under contention either.
        assertThat(balanceOf(source)).isEqualTo(ledgerEntries.derivedBalanceOf(source))
        assertThat(balanceOf(destination)).isEqualTo(ledgerEntries.derivedBalanceOf(destination))
    }

    @Test
    fun `transfers in both directions at once do not deadlock`() {
        val rounds = 25
        val left = fundedAccount("Ada Lovelace", 50_000)
        val right = fundedAccount("Katherine Johnson", 50_000)
        val totalBefore = balanceOf(left) + balanceOf(right)

        val pool = Executors.newFixedThreadPool(2)
        val startGate = CountDownLatch(1)
        val results = try {
            // Opposite directions: without a fixed lock order these two would each hold the row the
            // other is waiting for, and Postgres would abort one of them as a deadlock victim.
            val futures = listOf(left to right, right to left).map { (from, to) ->
                pool.submit<List<HttpStatus>> {
                    startGate.await()
                    (1..rounds).map { transfer(UUID.randomUUID(), from, to, 100).statusCode as HttpStatus }
                }
            }
            startGate.countDown()
            futures.flatMap { it.get(120, TimeUnit.SECONDS) }
        } finally {
            pool.shutdown()
            pool.awaitTermination(30, TimeUnit.SECONDS)
        }

        assertThat(results)
            .`as`("ordered locking turns contention into queueing, so every transfer succeeds")
            .containsOnly(HttpStatus.OK)
        assertThat(balanceOf(left) + balanceOf(right))
            .`as`("money is conserved: a transfer creates and destroys nothing")
            .isEqualTo(totalBefore)
    }

    @Test
    fun `a transfer larger than the balance is refused and writes nothing`() {
        val source = fundedAccount("Radia Perlman", 5_000)
        val destination = createAccount("Vint Cerf")

        val response = transfer(UUID.randomUUID(), source, destination, amount = 5_001)

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
        val problem = response.body!!
        assertThat(problem["title"].asText()).isEqualTo("Insufficient funds")
        assertThat(problem["balance"].asLong()).isEqualTo(5_000)
        assertThat(problem["requested"].asLong()).isEqualTo(5_001)

        assertThat(balanceOf(source)).isEqualTo(5_000)
        assertThat(balanceOf(destination)).isZero()
        assertThat(ledgerEntries.findByAccountId(destination, Pageable.unpaged()).totalElements)
            .`as`("a refused transfer leaves no half-written movement")
            .isZero()
    }

    @Test
    fun `a transfer to the same account is refused`() {
        val account = fundedAccount("Donald Knuth", 5_000)

        val response = transfer(UUID.randomUUID(), account, account, amount = 100)

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
        assertThat(response.body!!["title"].asText()).isEqualTo("Same account transfer")
        assertThat(balanceOf(account)).isEqualTo(5_000)
    }

    @Test
    fun `a transfer to an unknown account is a 404`() {
        val source = fundedAccount("Guido van Rossum", 5_000)

        val response = transfer(UUID.randomUUID(), source, 9_999_999, amount = 100)

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(response.body!!["type"].asText()).endsWith("/problems/account-not-found")
        assertThat(balanceOf(source)).isEqualTo(5_000)
    }

    @Test
    fun `a transfer in another currency is refused`() {
        val source = fundedAccount("Bjarne Stroustrup", 5_000)
        val destination = createAccount("James Gosling")

        val response = postJson(
            "/internal/transfers",
            mapOf(
                "transferId" to UUID.randomUUID(),
                "fromAccountId" to source,
                "toAccountId" to destination,
                "amount" to 100,
                "currency" to "USD",
            ),
            JsonNode::class.java,
            scope = DevTokens.SERVICE_SCOPE,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
        assertThat(response.body!!["title"].asText()).isEqualTo("Currency mismatch")
        assertThat(balanceOf(source)).isEqualTo(5_000)
    }

    private fun transfer(
        transferId: UUID,
        from: Long,
        to: Long,
        amount: Long,
    ): ResponseEntity<JsonNode> = postJson(
        "/internal/transfers",
        mapOf(
            "transferId" to transferId,
            "fromAccountId" to from,
            "toAccountId" to to,
            "amount" to amount,
            "currency" to "EUR",
        ),
        JsonNode::class.java,
        // /internal/** is service-to-service: a customer token is rejected with 403 by design.
        scope = DevTokens.SERVICE_SCOPE,
    )

    private fun createAccount(ownerName: String): Long {
        val response = postJson(
            "/accounts",
            mapOf("ownerName" to ownerName, "currency" to "EUR"),
            JsonNode::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        return response.body!!["id"].asLong()
    }

    private fun fundedAccount(ownerName: String, amount: Long): Long {
        val id = createAccount(ownerName)
        val response = postJson(
            "/accounts/$id/deposits",
            mapOf("transferId" to UUID.randomUUID(), "amount" to amount, "currency" to "EUR"),
            JsonNode::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        return id
    }

    private fun balanceOf(accountId: Long): Long = accounts.findById(accountId).orElseThrow().balance
}
