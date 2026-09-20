package dev.gokce.payments.account.infrastructure.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class PaymentEventListener(
    private val processedEvents: ProcessedEventRepository,
    private val objectMapper: ObjectMapper,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Consumes payment events idempotently.
     *
     * The order of the two steps is the guarantee: the database transaction commits first, and only
     * then does the container commit the offset. A crash in between means the record is delivered
     * again -- which is harmless, because the insert below claims the event id atomically and a second
     * delivery simply finds it taken.
     *
     * Doing it the other way round -- commit the offset, then process -- would be at-most-once, and a
     * crash would drop the event with nothing to show that it ever arrived.
     */
    @KafkaListener(
        topics = ["\${payment-events.topic}"],
        groupId = "\${payment-events.group-id}",
    )
    @Transactional
    fun onPaymentEvent(record: ConsumerRecord<String, String>) {
        // Throwing here is deliberate: a record that cannot be parsed is not retried, it is
        // dead-lettered. See KafkaConsumerConfiguration.
        val event = objectMapper.readValue(record.value(), PaymentEvent::class.java)

        val claimed = processedEvents.insertIfAbsent(
            eventId = event.eventId,
            eventType = event.eventType,
            paymentId = event.paymentId,
            topic = record.topic(),
            partitionNumber = record.partition(),
            kafkaOffset = record.offset(),
            payload = record.value(),
        )

        if (claimed == 0) {
            log.info(
                "Skipping duplicate event {} ({}) for payment {} at {}-{}@{}",
                event.eventId, event.eventType, event.paymentId,
                record.topic(), record.partition(), record.offset(),
            )
            return
        }

        log.info(
            "Recorded event {} ({}) for payment {} at {}-{}@{}",
            event.eventId, event.eventType, event.paymentId,
            record.topic(), record.partition(), record.offset(),
        )
    }
}
