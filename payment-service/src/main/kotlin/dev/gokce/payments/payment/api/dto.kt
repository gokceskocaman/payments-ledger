package dev.gokce.payments.payment.api

import com.fasterxml.jackson.annotation.JsonIgnore
import dev.gokce.payments.payment.domain.Payment
import dev.gokce.payments.payment.domain.PaymentStatus
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import java.time.Instant
import java.util.UUID

const val CURRENCY_PATTERN = "^[A-Z]{3}$"
private const val CURRENCY_MESSAGE = "must be a 3-letter uppercase ISO 4217 code"

@Schema(description = "A request to move money between two accounts")
data class CreatePaymentRequest(
    @field:Positive
    @field:Schema(example = "2", description = "Account the money leaves")
    val fromAccountId: Long,

    @field:Positive
    @field:Schema(example = "3", description = "Account the money arrives in")
    val toAccountId: Long,

    /** Minor units, e.g. 25000 for EUR 250.00. Never a decimal. */
    @field:Positive
    @field:Schema(example = "12000", description = "Amount in minor units; 12000 means EUR 120.00")
    val amount: Long,

    @field:Pattern(regexp = CURRENCY_PATTERN, message = CURRENCY_MESSAGE)
    @field:Schema(example = "EUR", description = "ISO 4217 alphabetic code")
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

@Schema(description = "A payment and its outcome. The id is also the transferId sent to account-service.")
data class PaymentResponse(
    @field:Schema(example = "f48631d1-f92d-4f5b-b657-fe882ab7fdc9") val id: UUID,
    @field:Schema(
        example = "COMPLETED",
        description = "PENDING means the outcome is not yet known -- retry or poll, do not assume failure",
    )
    val status: PaymentStatus,
    @field:Schema(example = "2") val fromAccountId: Long,
    @field:Schema(example = "3") val toAccountId: Long,
    @field:Schema(example = "12000", description = "Minor units") val amount: Long,
    @field:Schema(example = "EUR") val currency: String,
    @field:Schema(example = "null", description = "Set only when status is FAILED") val failureReason: String?,
    @field:Schema(example = "2026-09-20T09:26:32.748411Z") val createdAt: Instant,
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
