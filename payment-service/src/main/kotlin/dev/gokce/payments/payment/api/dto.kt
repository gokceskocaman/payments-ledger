package dev.gokce.payments.payment.api

import com.fasterxml.jackson.annotation.JsonIgnore
import dev.gokce.payments.payment.domain.Payment
import dev.gokce.payments.payment.domain.PaymentStatus
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import java.time.Instant
import java.util.UUID

const val CURRENCY_PATTERN = "^[A-Z]{3}$"
private const val CURRENCY_MESSAGE = "must be a 3-letter uppercase ISO 4217 code"

data class CreatePaymentRequest(
    @field:Positive
    val fromAccountId: Long,

    @field:Positive
    val toAccountId: Long,

    /** Minor units, e.g. 25000 for EUR 250.00. Never a decimal. */
    @field:Positive
    val amount: Long,

    @field:Pattern(regexp = CURRENCY_PATTERN, message = CURRENCY_MESSAGE)
    val currency: String,
) {
    /**
     * Rejected here rather than by the database CHECK, so the caller gets a 400 naming the problem
     * instead of a 500 from a constraint violation.
     */
    @get:AssertTrue(message = "fromAccountId and toAccountId must be different")
    @get:JsonIgnore
    val distinctAccounts: Boolean
        get() = fromAccountId != toAccountId
}

data class PaymentResponse(
    val id: UUID,
    val status: PaymentStatus,
    val fromAccountId: Long,
    val toAccountId: Long,
    val amount: Long,
    val currency: String,
    val failureReason: String?,
    val createdAt: Instant,
) {
    companion object {
        fun from(payment: Payment) = PaymentResponse(
            id = checkNotNull(payment.id) { "Payment has not been persisted" },
            status = payment.status,
            fromAccountId = payment.fromAccountId,
            toAccountId = payment.toAccountId,
            amount = payment.amount,
            currency = payment.currency,
            failureReason = payment.failureReason,
            createdAt = payment.createdAt,
        )
    }
}
