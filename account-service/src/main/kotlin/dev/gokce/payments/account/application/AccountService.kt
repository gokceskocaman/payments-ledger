package dev.gokce.payments.account.application

import dev.gokce.payments.account.domain.Account
import dev.gokce.payments.account.domain.AccountNotFoundException
import dev.gokce.payments.account.domain.AccountType
import dev.gokce.payments.account.domain.CurrencyMismatchException
import dev.gokce.payments.account.domain.CurrencyNotFundableException
import dev.gokce.payments.account.domain.Direction
import dev.gokce.payments.account.domain.LedgerEntry
import dev.gokce.payments.account.domain.SameAccountTransferException
import dev.gokce.payments.account.domain.SystemAccountNotAllowedException
import dev.gokce.payments.account.domain.TransferConflictException
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

        val locked = lockInAscendingIdOrder(fundingId, accountId)
        val funding = locked.getValue(fundingId)
        val account = locked.getValue(accountId)

        if (account.type != AccountType.CUSTOMER) {
            throw SystemAccountNotAllowedException(accountId, "a deposit")
        }

        // Safe under concurrency because the row lock above is still held: a racing replay of the
        // same transferId waits here, then sees the entry and returns the same balance.
        if (ledgerEntries.existsByTransferIdAndAccountId(transferId, accountId)) return account

        ledgerEntries.saveAll(
            listOf(
                LedgerEntry(fundingId, transferId, Direction.DEBIT, amount, currency),
                LedgerEntry(accountId, transferId, Direction.CREDIT, amount, currency),
            ),
        )
        funding.debit(amount)
        account.credit(amount)
        return account
    }

    /**
     * Moves money between two customer accounts: DEBIT the source, CREDIT the destination, two rows,
     * one transaction. Idempotent on `transferId`; refuses to overdraw the source.
     */
    @Transactional
    fun transfer(command: TransferCommand): PostedTransfer {
        if (command.fromAccountId == command.toAccountId) {
            // Besides being meaningless, a self-transfer would ask for the same row lock twice.
            throw SameAccountTransferException(command.fromAccountId)
        }

        // Projections before locks -- see deposit() above for why an entity read here would poison
        // the locked read that follows.
        val sourceCurrency = accounts.findCurrencyById(command.fromAccountId)
            ?: throw AccountNotFoundException(command.fromAccountId)
        val destinationCurrency = accounts.findCurrencyById(command.toAccountId)
            ?: throw AccountNotFoundException(command.toAccountId)
        if (sourceCurrency != command.currency) {
            throw CurrencyMismatchException(expected = sourceCurrency, actual = command.currency)
        }
        if (destinationCurrency != command.currency) {
            // One currency per transfer: this service does not convert money.
            throw CurrencyMismatchException(expected = destinationCurrency, actual = command.currency)
        }

        val locked = lockInAscendingIdOrder(command.fromAccountId, command.toAccountId)
        val source = locked.getValue(command.fromAccountId)
        val destination = locked.getValue(command.toAccountId)

        // Checked while both locks are held, so a concurrent replay blocks above and then lands here
        // after the first attempt has committed.
        ledgerEntries.findByTransferId(command.transferId)
            .takeIf { it.isNotEmpty() }
            ?.let { return replayOf(it, command) }

        if (source.type != AccountType.CUSTOMER) {
            throw SystemAccountNotAllowedException(command.fromAccountId, "a transfer")
        }
        if (destination.type != AccountType.CUSTOMER) {
            throw SystemAccountNotAllowedException(command.toAccountId, "a transfer")
        }

        // Balances move before the entries are written so an overdraft fails before any INSERT: with
        // IDENTITY keys, saving an entry hits the database immediately.
        source.debit(command.amount)
        destination.credit(command.amount)

        val entries = ledgerEntries.saveAll(
            listOf(
                LedgerEntry(
                    accountId = command.fromAccountId,
                    transferId = command.transferId,
                    direction = Direction.DEBIT,
                    amount = command.amount,
                    currency = command.currency,
                ),
                LedgerEntry(
                    accountId = command.toAccountId,
                    transferId = command.transferId,
                    direction = Direction.CREDIT,
                    amount = command.amount,
                    currency = command.currency,
                ),
            ),
        )

        return PostedTransfer(
            transferId = command.transferId,
            fromAccountId = command.fromAccountId,
            toAccountId = command.toAccountId,
            amount = command.amount,
            currency = command.currency,
            postedAt = entries.first().createdAt,
            replayed = false,
        )
    }

    /**
     * Takes one `SELECT ... FOR UPDATE` per account, always lowest id first.
     *
     * Ordering is the whole point: two transactions that lock the same pair in the same sequence can
     * only ever queue behind each other, never hold what the other one is waiting for. One query per
     * row rather than `where id in (..) order by id` because Postgres is free to lock rows before it
     * sorts them, so a single statement gives no ordering guarantee at all.
     */
    private fun lockInAscendingIdOrder(vararg accountIds: Long): Map<Long, Account> =
        accountIds.distinct().sorted().associateWith { id ->
            accounts.findByIdForUpdate(id) ?: throw AccountNotFoundException(id)
        }

    /** Answers a replay with the original movement, or refuses if the details do not match it. */
    private fun replayOf(existing: List<LedgerEntry>, command: TransferCommand): PostedTransfer {
        val debit = existing.singleOrNull { it.direction == Direction.DEBIT }
        val credit = existing.singleOrNull { it.direction == Direction.CREDIT }
        val matchesRequest = debit != null && credit != null &&
            debit.accountId == command.fromAccountId &&
            credit.accountId == command.toAccountId &&
            debit.amount == command.amount &&
            debit.currency == command.currency
        if (!matchesRequest) throw TransferConflictException(command.transferId)

        return PostedTransfer(
            transferId = command.transferId,
            fromAccountId = debit!!.accountId,
            toAccountId = credit!!.accountId,
            amount = debit.amount,
            currency = debit.currency,
            postedAt = debit.createdAt,
            replayed = true,
        )
    }
}
