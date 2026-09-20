package dev.gokce.payments.account

import com.fasterxml.jackson.databind.JsonNode
import dev.gokce.payments.account.domain.AccountType
import dev.gokce.payments.account.domain.Direction
import dev.gokce.payments.account.infrastructure.persistence.AccountRepository
import dev.gokce.payments.account.infrastructure.persistence.LedgerEntryRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

/**
 * Accounts, deposits and the ledger, exercised over real HTTP against a real Postgres. The schema
 * leans on partial unique indexes, CHECK constraints and a plpgsql trigger, so testing against H2
 * would test a different database than the one we ship.
 */
class AccountLedgerIntegrationTest @Autowired constructor(
    private val accounts: AccountRepository,
    private val ledgerEntries: LedgerEntryRepository,
    private val jdbc: JdbcTemplate,
) : PostgresTestBase() {

    @Test
    fun `creating an account returns it with a zero balance`() {
        val account = createAccount("Ada Lovelace")

        assertThat(account["ownerName"].asText()).isEqualTo("Ada Lovelace")
        assertThat(account["currency"].asText()).isEqualTo("EUR")
        assertThat(account["balance"].asLong()).isZero()
    }

    @Test
    fun `a deposit credits the account, debits the funding account and balances to zero`() {
        val accountId = createAccount("Grace Hopper")["id"].asLong()
        val fundingBefore = fundingBalance()
        val transferId = UUID.randomUUID()

        val response = deposit(accountId, transferId, amount = 25_000)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body!!["balance"].asLong()).isEqualTo(25_000)
        assertThat(getAccount(accountId)["balance"].asLong()).isEqualTo(25_000)

        val entries = ledgerEntries.findByTransferId(transferId)
        assertThat(entries)
            .`as`("double entry: one movement writes exactly two rows")
            .hasSize(2)
        assertThat(entries.map { it.direction })
            .containsExactlyInAnyOrder(Direction.DEBIT, Direction.CREDIT)
        assertThat(entries.sumOf { it.signed() })
            .`as`("the two sides of a movement must cancel out")
            .isZero()
        assertThat(entries.map { it.amount })
            .`as`("amounts are stored positive; the sign lives in direction")
            .containsOnly(25_000)

        assertThat(fundingBalance())
            .`as`("the funding account carries the other side of the deposit")
            .isEqualTo(fundingBefore - 25_000)
    }

    @Test
    fun `the cached balance always equals the balance derived from the ledger`() {
        val accountId = createAccount("Joan Clarke")["id"].asLong()
        deposit(accountId, UUID.randomUUID(), amount = 7_500)
        deposit(accountId, UUID.randomUUID(), amount = 2_500)

        val cached = accounts.findById(accountId).orElseThrow().balance
        assertThat(cached).isEqualTo(10_000)
        assertThat(cached)
            .`as`("accounts.balance is a projection of the entries, never an independent value")
            .isEqualTo(ledgerEntries.derivedBalanceOf(accountId))
    }

    @Test
    fun `replaying a deposit with the same transferId does not post it twice`() {
        val accountId = createAccount("Katherine Johnson")["id"].asLong()
        val transferId = UUID.randomUUID()

        deposit(accountId, transferId, amount = 10_000)
        val replay = deposit(accountId, transferId, amount = 10_000)

        assertThat(replay.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(replay.body!!["balance"].asLong())
            .`as`("a replay returns the balance the first call produced")
            .isEqualTo(10_000)
        assertThat(ledgerEntries.findByAccountId(accountId, Pageable.unpaged()).totalElements)
            .isEqualTo(1)
    }

    @Test
    fun `entries are paginated, newest first`() {
        val accountId = createAccount("Margaret Hamilton")["id"].asLong()
        listOf(1_000L, 2_000L, 3_000L).forEach { deposit(accountId, UUID.randomUUID(), it) }

        val page = getJson("/accounts/$accountId/entries?page=0&size=2", JsonNode::class.java).body!!

        assertThat(page["totalElements"].asLong()).isEqualTo(3)
        assertThat(page["totalPages"].asInt()).isEqualTo(2)
        assertThat(page["content"].size()).isEqualTo(2)
        assertThat(page["content"][0]["amount"].asLong())
            .`as`("default sort is id descending, so the newest entry leads")
            .isEqualTo(3_000)
        assertThat(page["content"][0]["direction"].asText()).isEqualTo("CREDIT")
    }

    @Test
    fun `a deposit in another currency is rejected`() {
        val accountId = createAccount("Barbara Liskov")["id"].asLong()

        val response = depositRaw(accountId, UUID.randomUUID(), amount = 500, currency = "USD")

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
        val problem = response.body!!
        assertThat(problem["title"].asText()).isEqualTo("Currency mismatch")
        assertThat(problem["expected"].asText()).isEqualTo("EUR")
        assertThat(problem["actual"].asText()).isEqualTo("USD")
        assertThat(ledgerEntries.findByAccountId(accountId, Pageable.unpaged()).totalElements)
            .`as`("a rejected deposit writes nothing")
            .isZero()
    }

    @Test
    fun `an invalid request body is answered with field-level problem details`() {
        val response = postJson(
            "/accounts",
            mapOf("ownerName" to "   ", "currency" to "eur"),
            JsonNode::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        val problem = response.body!!
        assertThat(problem["title"].asText()).isEqualTo("Validation failed")
        assertThat(problem["errors"].map { it["field"].asText() })
            .containsExactlyInAnyOrder("ownerName", "currency")
    }

    @Test
    fun `a missing json field is reported by name`() {
        val accountId = createAccount("Radia Perlman")["id"].asLong()

        val response = postJson(
            "/accounts/$accountId/deposits",
            mapOf("amount" to 100, "currency" to "EUR"),
            JsonNode::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.body!!["detail"].asText()).contains("transferId")
    }

    @Test
    fun `an unknown account is a 404 problem detail`() {
        val response = getJson("/accounts/999999", JsonNode::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(response.body!!["type"].asText()).endsWith("/problems/account-not-found")
    }

    @Test
    fun `the database refuses to overdraw a customer account`() {
        val accountId = createAccount("Anita Borg")["id"].asLong()

        assertThatThrownBy { jdbc.update("update accounts set balance = -1 where id = ?", accountId) }
            .`as`("the CHECK constraint is the last line of defence behind the service")
            .hasMessageContaining("accounts_customer_balance_non_negative")
    }

    @Test
    fun `the ledger is append-only`() {
        val accountId = createAccount("Sophie Wilson")["id"].asLong()
        deposit(accountId, UUID.randomUUID(), amount = 4_200)
        val entryId = ledgerEntries.findByAccountId(accountId, Pageable.unpaged()).content.first().id

        assertThatThrownBy { jdbc.update("update ledger_entries set amount = 1 where id = ?", entryId) }
            .hasMessageContaining("append-only")
        assertThatThrownBy { jdbc.update("delete from ledger_entries where id = ?", entryId) }
            .hasMessageContaining("append-only")
    }

    private fun createAccount(ownerName: String, currency: String = "EUR"): JsonNode {
        val response = postJson(
            "/accounts",
            mapOf("ownerName" to ownerName, "currency" to currency),
            JsonNode::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        return response.body!!
    }

    private fun getAccount(id: Long): JsonNode =
        getJson("/accounts/$id", JsonNode::class.java).body!!

    private fun deposit(accountId: Long, transferId: UUID, amount: Long) =
        depositRaw(accountId, transferId, amount, "EUR")

    private fun depositRaw(accountId: Long, transferId: UUID, amount: Long, currency: String) =
        postJson(
            "/accounts/$accountId/deposits",
            mapOf("transferId" to transferId, "amount" to amount, "currency" to currency),
            JsonNode::class.java,
        )

    private fun fundingBalance(): Long {
        val fundingId = accounts.findIdByTypeAndCurrency(AccountType.SYSTEM, "EUR")
        assertThat(fundingId).`as`("migration seeds the EUR funding account").isNotNull()
        return accounts.findById(fundingId!!).orElseThrow().balance
    }

    private fun dev.gokce.payments.account.domain.LedgerEntry.signed(): Long =
        if (direction == Direction.CREDIT) amount else -amount
}
