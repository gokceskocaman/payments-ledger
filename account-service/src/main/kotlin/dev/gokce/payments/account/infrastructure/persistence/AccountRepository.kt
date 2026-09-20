package dev.gokce.payments.account.infrastructure.persistence

import dev.gokce.payments.account.domain.Account
import dev.gokce.payments.account.domain.AccountType
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface AccountRepository : JpaRepository<Account, Long> {

    /**
     * Takes a `SELECT ... FOR UPDATE` row lock, serialising every writer of this account.
     *
     * Call this *before* loading the account any other way. A plain read followed by a locked read
     * returns the copy already in the persistence context, so the balance you then write would be
     * computed from data that went stale the moment another transaction committed.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    fun findByIdForUpdate(@Param("id") id: Long): Account?

    /** Projections, so that checking a precondition does not load an entity we intend to lock. */
    @Query("select a.currency from Account a where a.id = :id")
    fun findCurrencyById(@Param("id") id: Long): String?

    @Query("select a.id from Account a where a.type = :type and a.currency = :currency")
    fun findIdByTypeAndCurrency(
        @Param("type") type: AccountType,
        @Param("currency") currency: String,
    ): Long?
}
