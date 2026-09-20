package dev.gokce.payments.payment.infrastructure.outbox

import dev.gokce.payments.payment.domain.OutboxEvent
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Moves committed events to Kafka.
 *
 * The guarantee is **at-least-once**, and the ordering of the three steps is what makes it so:
 * claim the row, publish, then mark it published. A crash between the publish and the mark leaves
 * the row unclaimed, so the next tick sends it again -- a duplicate, never a loss. Marking first
 * would trade that for at-most-once, where a crash loses the event silently, which is the worse
 * failure for a payments system. Consumers therefore have to deduplicate on `eventId`.
 */
@Component
class OutboxRelay(
    private val outbox: OutboxEventRepository,
    private val kafka: KafkaTemplate<String, String>,
    private val properties: OutboxProperties,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${outbox.poll-interval:1s}")
    @Transactional
    fun publishPending() {
        val batch = outbox.claimUnpublished(properties.batchSize)
        if (batch.isEmpty()) return

        for (event in batch) {
            try {
                publish(event)
                event.markPublished()
            } catch (e: Exception) {
                // Stop at the first failure rather than skipping past it: events for one payment
                // share a key and must stay in order, and if the broker is unhealthy the rest of the
                // batch will fail too. The row keeps its place and the next tick retries it.
                event.recordFailure(e.message)
                log.warn(
                    "Outbox event {} ({}) failed to publish on attempt {}: {}",
                    event.id, event.eventType, event.attempts, e.message,
                )
                break
            }
        }
    }

    /**
     * Blocking on purpose. An event may only be marked published once the broker has acknowledged
     * it; a fire-and-forget send would mark rows sent that are still sitting in a producer buffer.
     */
    private fun publish(event: OutboxEvent) {
        val record = ProducerRecord(properties.topic, event.aggregateId.toString(), event.payload)
        record.headers()
            .add("event-id", event.id.toString().toByteArray(StandardCharsets.UTF_8))
            .add("event-type", event.eventType.name.toByteArray(StandardCharsets.UTF_8))

        kafka.send(record).get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private companion object {
        const val REQUEST_TIMEOUT_SECONDS = 10L
    }
}
