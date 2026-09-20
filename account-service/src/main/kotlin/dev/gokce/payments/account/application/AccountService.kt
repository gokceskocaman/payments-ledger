package dev.gokce.payments.account.application

import dev.gokce.payments.account.domain.Account
import dev.gokce.payments.account.domain.AccountNotFoundException
import dev.gokce.payments.account.domain.AccountType
import dev.gokce.payments.account.domain.CurrencyMismatchException
import dev.gokce.payments.account.domain.CurrencyNotFundableException
import dev.gokce.payments.account.domain.DepositNotAllowedException
import dev.gokce.payments.account.domain.Direction
import dev.gokce.payments.account.domain.LedgerEntry
import dev.gokce.payments.account.infrastructure.persistence.AccountRepository
import dev.gokce.payments.account.infrastructure.persistence.LedgerEntryRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class AccountService(
    private val accounts: AccountRepository,
    private val ledgerEntries: LedgerEntryRepository,
) {

    @Transactional
    fun createAccount(ownerName: String, currency: String): Account =
        accounts.save(Account(ownerName = ownerName.trim(), currency = currency))

    @Transactional(readOnly = true)
    fun getAccount(id: Long): Account =
        accounts.findById(id).orElseThrow { AccountNotFoundException(id) }

    @Transactional(readOnly = true)
    fun listEntries(accountId: Long, pageable: Pageable): Page<LedgerEntry> {
        if (!accounts.existsById(accountId)) throw AccountNotFoundException(accountId)
        return ledgerEntries.findByAccountId(accountId, pageable)
    }

    /**
     * Funds an account: DEBIT the system account, CREDIT the customer account, two rows, one
     * transaction. Idempotent on [transferId] -- replaying a deposit returns the balance the
     * first call produced instead of posting it again.
     */
    @Transactional
    fun deposit(accountId: Long, transferId: UUID, amount: Long, currency: String): Account {
        // Preconditions are checked against projections rather than entities: loading the account
        // here would put a pre-lock copy in the persistence context and the locked read below
        // would hand it straight back.
        val accountCurrency = accounts.findCurrencyById(accountId) ?: throw AccountNotFoundException(accountId)
        if (accountCurrency != currency) {
            throw CurrencyMismatchException(expected = accountCurrency, actual = currency)
        }
        val fundingId = accounts.findIdByTypeAndCurrency(AccountType.SYSTEM, currency)
            ?: throw CurrencyNotFundableException(currency)

        // Deadlock avoidance: locks are taken one row at a time, always in ascending id order.
        // Two movements touching the same pair of accounts therefore queue instead of deadlocking.
        val locked = listOf(fundingId, accountId).distinct().sorted()
            .associateWith { accounts.findByIdForUpdate(it) ?: throw AccountNotFoundException(it) }
        val funding = locked.getValue(fundingId)
        val account = locked.getValue(accountId)

        if (account.type != AccountType.CUSTOMER) throw DepositNotAllowedException(accountId)

        // Safe under concurrency because the row lock above is still held: a racing replay of the
        // same transferId waits here, then sees the entry and returns the same balance.
        if (ledgerEntries.existsByTransferIdAndAccountId(transferId, accountId)) return account

        ledgerEntries.saveAll(
            listOf(
                LedgerEntry(
                    accountId = fundingId,
                    transferId = transferId,
                    direction = Direction.DEBIT,
                    amount = amount,
                    currency = currency,
                ),
                LedgerEntry(
                    accountId = accountId,
                    transferId = transferId,
                    direction = Direction.CREDIT,
                    amount = amount,
                    currency = currency,
                ),
            ),
        )
        funding.debit(amount)
        account.credit(amount)
        return account
    }
}
