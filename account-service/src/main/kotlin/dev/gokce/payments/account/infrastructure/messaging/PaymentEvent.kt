package dev.gokce.payments.account.infrastructure.messaging

import java.time.Instant
import java.util.UUID

/**
 * A payment event as it arrives on the wire.
 *
 * Every field is non-null on purpose: a record missing its `eventId` cannot be deduplicated and one
 * missing its `paymentId` cannot be attributed, so Jackson failing to bind is the correct outcome.
 * That failure is classified as non-retryable and goes straight to the dead-letter topic -- retrying
 * a malformed record just delays the partition for no reason.
 */
data class PaymentEvent(
    val eventId: UUID,
    val eventType: String,
    val paymentId: UUID,
    val amount: Long,
    val currency: String,
    val status: String,
    val occurredAt: Instant,
)
