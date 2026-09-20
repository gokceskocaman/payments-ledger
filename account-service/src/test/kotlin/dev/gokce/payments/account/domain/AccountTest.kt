package dev.gokce.payments.account.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.temporal.ChronoUnit

class AccountTest {

    private fun customer() = Account(ownerName = "Ada Lovelace", currency = "EUR")
    private fun system() = Account(
        ownerName = "System funding account (EUR)",
        currency = "EUR",
        type = AccountType.SYSTEM,
    )

    @Test
    fun `a new account starts empty`() {
        assertThat(customer().balance).isZero()
    }

    @Test
    fun `crediting raises the balance`() {
        val account = customer()
        account.credit(25_000)
        account.credit(1)
        assertThat(account.balance).isEqualTo(25_001)
    }

    @Test
    fun `a customer account cannot be debited below zero`() {
        val account = customer().apply { credit(10_000) }

        assertThatThrownBy { account.debit(10_001) }
            .isInstanceOf(InsufficientFundsException::class.java)

        assertThat(account.balance)
            .`as`("a rejected debit must leave the balance untouched")
            .isEqualTo(10_000)
    }

    @Test
    fun `a customer account may be emptied exactly`() {
        val account = customer().apply { credit(10_000) }
        account.debit(10_000)
        assertThat(account.balance).isZero()
    }

    @Test
    fun `the system account is allowed to go negative`() {
        val funding = system()
        funding.debit(25_000)
        assertThat(funding.balance)
            .`as`("money entering the ledger is owed by the funding account")
            .isEqualTo(-25_000)
    }

    @Test
    fun `amounts must be positive`() {
        assertThatThrownBy { customer().credit(0) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { customer().debit(-1) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `timestamps carry no precision the database cannot store`() {
        // Postgres timestamptz keeps microseconds. A nanosecond-resolution clock -- which Linux has and
        // macOS does not -- would otherwise make the value returned by a write differ from the value
        // returned by every later read of the same row.
        val account = customer()
        assertThat(account.createdAt).isEqualTo(account.createdAt.truncatedTo(ChronoUnit.MICROS))

        val entry = LedgerEntry(
            accountId = 1,
            transferId = java.util.UUID.randomUUID(),
            direction = Direction.CREDIT,
            amount = 100,
            currency = "EUR",
        )
        assertThat(entry.createdAt).isEqualTo(entry.createdAt.truncatedTo(ChronoUnit.MICROS))
    }
}
