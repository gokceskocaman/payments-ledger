package dev.gokce.payments.account.infrastructure.persistence

import dev.gokce.payments.account.domain.LedgerEntry
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface LedgerEntryRepository : JpaRepository<LedgerEntry, Long> {

    fun findByAccountId(accountId: Long, pageable: Pageable): Page<LedgerEntry>

    /** Idempotency check: has this side of this movement already been posted? */
    fun existsByTransferIdAndAccountId(transferId: UUID, accountId: Long): Boolean

    fun findByTransferId(transferId: UUID): List<LedgerEntry>

    /**
     * The balance as the ledger defines it: credits minus debits. `accounts.balance` must always
     * equal this. Reconciliation reads it; balance reads do not, because it scans every entry
     * an account has ever had.
     */
    @Query(
        value = """
            select coalesce(sum(case when direction = 'CREDIT' then amount else -amount end), 0)
            from ledger_entries
            where account_id = :accountId
        """,
        nativeQuery = true,
    )
    fun derivedBalanceOf(@Param("accountId") accountId: Long): Long
}
