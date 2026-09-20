package dev.gokce.payments.payment.infrastructure.outbox

import com.fasterxml.jackson.databind.ObjectMapper
import dev.gokce.payments.payment.domain.OutboxEvent
import dev.gokce.payments.payment.domain.Payment
import dev.gokce.payments.payment.domain.PaymentEventType
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * Records a domain event.
 *
 * Has no transaction management of its own on purpose: it must run inside whichever transaction is
 * changing the payment, because "the state change and its event commit together" is the entire
 * guarantee the outbox provides.
 */
@Component
class OutboxWriter(
    private val outbox: OutboxEventRepository,
    private val objectMapper: ObjectMapper,
) {

    fun record(payment: Payment, eventType: PaymentEventType): OutboxEvent {
        val paymentId = checkNotNull(payment.id) { "Cannot record an event for an unsaved payment" }
        val eventId = UUID.randomUUID()

        val payload = objectMapper.writeValueAsString(
            mapOf(
                // Carried in the payload as well as in a Kafka header, so a consumer can deduplicate
                // without depending on broker metadata surviving every hop.
                "eventId" to eventId,
                "eventType" to eventType,
                "occurredAt" to Instant.now(),
                "paymentId" to paymentId,
                "fromAccountId" to payment.fromAccountId,
                "toAccountId" to payment.toAccountId,
                "amount" to payment.amount,
                "currency" to payment.currency,
                "status" to payment.status,
                "failureReason" to payment.failureReason,
            ),
        )

        return outbox.save(
            OutboxEvent(
                id = eventId,
                aggregateId = paymentId,
                aggregateType = "Payment",
                eventType = eventType,
                payload = payload,
            ),
        )
    }
}
