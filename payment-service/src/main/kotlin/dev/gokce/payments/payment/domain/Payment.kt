package dev.gokce.payments.payment.domain

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
import java.util.UUID

/**
 * A request to move money, and its outcome.
 *
 * The id doubles as the `transferId` sent to account-service. That is the single most important
 * decision in this service: because the identifier is derived from the payment rather than from the
 * attempt, retrying a transfer any number of times can only ever produce one movement.
 */
@Entity
@Table(name = "payments")
class Payment(
    @Column(name = "idempotency_key", nullable = false, length = 255)
    val idempotencyKey: String,

    @Column(name = "request_hash", nullable = false, length = 64)
    val requestHash: String,

    @Column(name = "from_account_id", nullable = false)
    val fromAccountId: Long,

    @Column(name = "to_account_id", nullable = false)
    val toAccountId: Long,

    @Column(nullable = false)
    val amount: Long,

    @Column(nullable = false, length = 3)
    val currency: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null
        private set

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: PaymentStatus = PaymentStatus.PENDING
        private set

    @Column(name = "failure_reason", length = 500)
    var failureReason: String? = null
        private set

    /**
     * Truncated to microseconds because that is all `timestamptz` keeps. Without it the value held in
     * memory differs from the value every later read returns -- invisible on macOS, where the clock is
     * already microsecond-resolution, and wrong on Linux, where it is not.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS)

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS)
        private set

    /** Only after account-service has confirmed the movement. */
    fun complete() {
        transitionTo(PaymentStatus.COMPLETED)
    }

    /**
     * Only on a *definitive* refusal -- account-service decided not to move the money and wrote
     * nothing. A timeout or a 5xx is not a refusal: see PaymentService for why those leave the
     * payment PENDING instead.
     */
    fun fail(reason: String) {
        transitionTo(PaymentStatus.FAILED)
        failureReason = reason.take(500)
    }

    private fun transitionTo(target: PaymentStatus) {
        if (status != PaymentStatus.PENDING) throw IllegalPaymentTransitionException(status, target)
        status = target
        updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS)
    }

    fun matches(requestHash: String): Boolean = this.requestHash == requestHash
}
