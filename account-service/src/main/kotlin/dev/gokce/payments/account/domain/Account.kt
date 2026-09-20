package dev.gokce.payments.account.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * An account and its cached balance.
 *
 * The authoritative balance is the sum of this account's [LedgerEntry] rows; [balance] is a
 * projection of that sum, maintained in the same transaction as the entries. Callers therefore
 * cannot move the balance directly -- they post entries and call [credit] / [debit], which keeps
 * the two representations in step.
 */
@Entity
@Table(name = "accounts")
class Account(
    @Column(name = "owner_name", nullable = false, length = 200)
    val ownerName: String,

    @Column(nullable = false, length = 3)
    val currency: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 16)
    val type: AccountType = AccountType.CUSTOMER,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        private set

    @Column(nullable = false)
    var balance: Long = 0
        private set

    /**
     * Truncated to microseconds because that is all `timestamptz` keeps. Without it the value held in
     * memory differs from the value every later read returns -- invisible on macOS, where the clock is
     * already microsecond-resolution, and wrong on Linux, where it is not.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS)

    fun credit(amount: Long) {
        requirePositive(amount)
        balance += amount
    }

    /**
     * @throws InsufficientFundsException if this would overdraw a customer account. A SYSTEM
     * account is exempt: it is the source of funds entering the ledger, so going negative is
     * its normal state.
     */
    fun debit(amount: Long) {
        requirePositive(amount)
        val remaining = balance - amount
        if (remaining < 0 && type != AccountType.SYSTEM) {
            throw InsufficientFundsException(id, balance, amount)
        }
        balance = remaining
    }

    private fun requirePositive(amount: Long) =
        require(amount > 0) { "Amount must be positive in minor units, was $amount" }
}
