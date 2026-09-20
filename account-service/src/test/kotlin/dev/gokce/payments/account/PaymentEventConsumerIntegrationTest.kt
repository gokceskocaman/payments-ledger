package dev.gokce.payments.account

import dev.gokce.payments.account.infrastructure.messaging.PaymentEventsProperties
import dev.gokce.payments.account.infrastructure.messaging.ProcessedEventRepository
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringDeserializer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.kafka.core.KafkaTemplate
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The consumer side of the at-least-once contract: an event is recorded once, a redelivery of the
 * same event changes nothing, and a record that can never be processed ends up on the dead-letter
 * topic instead of blocking its partition.
 */
class PaymentEventConsumerIntegrationTest @Autowired constructor(
    private val processedEvents: ProcessedEventRepository,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val properties: PaymentEventsProperties,
) : PostgresTestBase() {

    companion object {
        private lateinit var dltConsumer: KafkaConsumer<String, String>

        @BeforeAll
        @JvmStatic
        fun subscribeToDeadLetterTopic() {
            dltConsumer = KafkaConsumer(
                mapOf(
                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers,
                    ConsumerConfig.GROUP_ID_CONFIG to "dlt-test-${UUID.randomUUID()}",
                    ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
                    ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
                    ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
                ),
            )
            dltConsumer.subscribe(listOf("payments.events.DLT"))
        }

        @AfterAll
        @JvmStatic
        fun close() = dltConsumer.close()
    }

    @Test
    fun `a payment event is recorded once`() {
        val eventId = UUID.randomUUID()
        val paymentId = UUID.randomUUID()

        publish(eventId, paymentId, "PaymentCompleted", amount = 12_000)

        val recorded = eventually { processedEvents.findById(eventId).orElseThrow() }
        assertThat(recorded.paymentId).isEqualTo(paymentId)
        assertThat(recorded.eventType).isEqualTo("PaymentCompleted")
        assertThat(recorded.topic).isEqualTo(properties.topic)
        assertThat(processedEvents.countByPaymentId(paymentId)).isEqualTo(1)
    }

    @Test
    fun `a duplicated event is processed once`() {
        val eventId = UUID.randomUUID()
        val paymentId = UUID.randomUUID()

        // The same event id twice -- exactly what a redelivery looks like from here, whether it came
        // from a consumer crash, a rebalance, or the producer's relay resending after a failure.
        publish(eventId, paymentId, "PaymentCompleted", amount = 7_500)
        val first = eventually { processedEvents.findById(eventId).orElseThrow() }

        publish(eventId, paymentId, "PaymentCompleted", amount = 7_500)

        // Wait out several poll cycles so the second delivery has certainly been handled, then check
        // that handling it changed nothing at all.
        Thread.sleep(1_000)
        assertThat(processedEvents.countByPaymentId(paymentId))
            .`as`("the second delivery must not add a row")
            .isEqualTo(1)

        val after = processedEvents.findById(eventId).orElseThrow()
        assertThat(after.kafkaOffset)
            .`as`("the row still records the first delivery; the duplicate did not overwrite it")
            .isEqualTo(first.kafkaOffset)
        assertThat(after.processedAt).isEqualTo(first.processedAt)
    }

    @Test
    fun `two different events for the same payment are both recorded`() {
        val paymentId = UUID.randomUUID()
        val created = UUID.randomUUID()
        val completed = UUID.randomUUID()

        publish(created, paymentId, "PaymentCreated", amount = 3_000)
        publish(completed, paymentId, "PaymentCompleted", amount = 3_000)

        eventually {
            assertThat(processedEvents.countByPaymentId(paymentId))
                .`as`("deduplication is per event, not per payment")
                .isEqualTo(2)
        }
    }

    @Test
    fun `an unparseable record goes to the dead letter topic`() {
        val key = UUID.randomUUID().toString()
        val recordedBefore = processedEvents.count()

        // Not valid JSON for a PaymentEvent: no amount of retrying will fix it, so the handler must
        // not keep the partition busy trying.
        kafkaTemplate.send(ProducerRecord(properties.topic, key, """{"eventId":"not-a-uuid"}""")).get()

        val dead = eventually(Duration.ofSeconds(20)) {
            val records = pollDeadLetters()
            assertThat(records.map { it.key() }).contains(key)
            records.first { it.key() == key }
        }
        assertThat(dead.value()).contains("not-a-uuid")
        assertThat(String(dead.headers().lastHeader("kafka_dlt-exception-fqcn").value(), StandardCharsets.UTF_8))
            .`as`("the DLT record carries why it failed")
            .contains("Exception")
        assertThat(processedEvents.count())
            .`as`("a dead-lettered record is never recorded as processed")
            .isEqualTo(recordedBefore)
    }

    private fun publish(eventId: UUID, paymentId: UUID, eventType: String, amount: Long) {
        val payload = """
            {"eventId":"$eventId","eventType":"$eventType","occurredAt":"${Instant.now()}",
             "paymentId":"$paymentId","fromAccountId":1,"toAccountId":2,
             "amount":$amount,"currency":"EUR","status":"COMPLETED","failureReason":null}
        """.trimIndent()
        // Keyed by payment id, as the outbox relay does.
        kafkaTemplate.send(ProducerRecord(properties.topic, paymentId.toString(), payload)).get()
    }

    private val deadLetters = mutableListOf<ConsumerRecord<String, String>>()

    private fun pollDeadLetters(): List<ConsumerRecord<String, String>> {
        dltConsumer.poll(Duration.ofMillis(500)).forEach { deadLetters += it }
        return deadLetters
    }

    private fun <T> eventually(timeout: Duration = Duration.ofSeconds(15), block: () -> T): T {
        val deadline = System.nanoTime() + timeout.toNanos()
        var last: Throwable? = null
        while (System.nanoTime() < deadline) {
            try {
                return block()
            } catch (e: AssertionError) {
                last = e
                Thread.sleep(100)
            } catch (e: NoSuchElementException) {
                last = e
                Thread.sleep(100)
            }
        }
        throw AssertionError("Condition not met within $timeout", last)
    }
}
