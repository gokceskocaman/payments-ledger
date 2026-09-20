package dev.gokce.payments.payment.infrastructure

import dev.gokce.payments.payment.domain.Payment
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface PaymentRepository : JpaRepository<Payment, UUID> {

    fun findByIdempotencyKey(idempotencyKey: String): Payment?

    /**
     * Locks the payment row while its outcome is written. Two requests carrying the same
     * Idempotency-Key can both reach the transfer call -- which is safe, because account-service
     * deduplicates on transferId -- and this makes writing the result safe too.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    fun findByIdForUpdate(@Param("id") id: UUID): Payment?
}
