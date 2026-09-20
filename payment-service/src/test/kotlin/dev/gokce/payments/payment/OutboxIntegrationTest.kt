package dev.gokce.payments.payment

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import dev.gokce.payments.payment.domain.PaymentEventType
import dev.gokce.payments.payment.infrastructure.outbox.OutboxEventRepository
import dev.gokce.payments.payment.infrastructure.outbox.OutboxProperties
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID

/**
 * The outbox end to end: a payment writes events in its own transaction, the relay moves them to a
 * real Kafka broker, and the rows are marked published only once the broker has them.
 */
class OutboxIntegrationTest @Autowired constructor(
    private val rest: TestRestTemplate,
    private val outbox: OutboxEventRepository,
    private val outboxProperties: OutboxProperties,
    private val objectMapper: ObjectMapper,
) : PaymentServiceTestBase() {

    companion object {
        private lateinit var consumer: KafkaConsumer<String, String>

        /**
         * Shared with the consumer that fills it. JUnit builds a fresh test instance per method,
         * so a per-instance buffer would throw away records polled by an earlier test -- and the
         * consumer's offsets would already have moved past them.
         */
        private val consumed = mutableListOf<ConsumerRecord<String, String>>()

        @BeforeAll
        @JvmStatic
        fun subscribe() {
            consumer = KafkaConsumer(
                mapOf(
                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers,
                    ConsumerConfig.GROUP_ID_CONFIG to "outbox-test-${UUID.randomUUID()}",
                    ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
                    ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
                    ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
                ),
            )
            consumer.subscribe(listOf("payments.events"))
        }

        @AfterAll
        @JvmStatic
        fun unsubscribe() = consumer.close()
    }

    @Test
    fun `a completed payment emits PaymentCreated then PaymentCompleted, keyed by payment id`() {
        accountServiceAccepts()

        val paymentId = pay(key = "outbox-completed", amount = 12_000)

        val events = eventually { rowsFor(paymentId, expected = 2) }
        assertThat(events.map { it.eventType })
            .containsExactly(PaymentEventType.PaymentCreated, PaymentEventType.PaymentCompleted)
        assertThat(events).allSatisfy {
            assertThat(it.publishedAt).`as`("the relay marks a row only after the broker acknowledges").isNotNull()
            assertThat(it.attempts).isZero()
            assertThat(it.lastError).isNull()
        }

        val published = eventually { recordsFor(paymentId, expected = 2) }
        assertThat(published.map { it.key() })
            .`as`("keyed by payment id, so one payment's events share a partition and stay ordered")
            .containsOnly(paymentId.toString())
        assertThat(published.map { header(it, "event-type") })
            .containsExactly("PaymentCreated", "PaymentCompleted")

        val completed = objectMapper.readTree(published.last().value())
        assertThat(completed["paymentId"].asText()).isEqualTo(paymentId.toString())
        assertThat(completed["status"].asText()).isEqualTo("COMPLETED")
        assertThat(completed["amount"].asLong()).isEqualTo(12_000)
        assertThat(completed["eventId"].asText())
            .`as`("the id a consumer deduplicates on travels in the payload as well as the header")
            .isEqualTo(header(published.last(), "event-id"))
            .isEqualTo(events.last().id.toString())
    }

    @Test
    fun `a refused payment emits PaymentCreated then PaymentFailed`() {
        accountService.stubFor(
            post(urlEqualTo("/internal/transfers")).willReturn(
                aResponse().withStatus(422)
                    .withHeader("Content-Type", "application/problem+json")
                    .withBody("""{"title":"Insufficient funds","detail":"Account 1 holds 500"}"""),
            ),
        )

        val paymentId = pay(key = "outbox-failed", amount = 99_000)

        val events = eventually { rowsFor(paymentId, expected = 2) }
        assertThat(events.map { it.eventType })
            .containsExactly(PaymentEventType.PaymentCreated, PaymentEventType.PaymentFailed)

        val published = eventually { recordsFor(paymentId, expected = 2) }
        val failed = objectMapper.readTree(published.last().value())
        assertThat(failed["status"].asText()).isEqualTo("FAILED")
        assertThat(failed["failureReason"].asText()).contains("Account 1 holds 500")
    }

    @Test
    fun `an unresolved payment emits only PaymentCreated`() {
        accountService.stubFor(
            post(urlEqualTo("/internal/transfers"))
                .willReturn(aResponse().withFixedDelay(2_000).withStatus(200).withBody("{}")),
        )

        val paymentId = pay(key = "outbox-pending", amount = 5_000, expectedStatus = HttpStatus.ACCEPTED)

        val events = eventually { rowsFor(paymentId, expected = 1) }
        assertThat(events.single().eventType).isEqualTo(PaymentEventType.PaymentCreated)

        // Give the relay several ticks: a terminal event must not appear later either, because none
        // was ever written. PENDING is not an outcome to announce.
        Thread.sleep(500)
        assertThat(outbox.findByAggregateIdOrderByOccurredAt(paymentId))
            .`as`("a payment whose outcome is unknown announces no outcome")
            .hasSize(1)
    }

    @Test
    fun `published rows are not published again`() {
        accountServiceAccepts()
        val paymentId = pay(key = "outbox-once", amount = 3_000)

        val events = eventually { rowsFor(paymentId, expected = 2) }
        val publishedAt = events.map { it.publishedAt }

        // Several relay ticks later the timestamps are untouched: the claim query only ever sees
        // rows whose published_at is null.
        Thread.sleep(500)
        assertThat(outbox.findByAggregateIdOrderByOccurredAt(paymentId).map { it.publishedAt })
            .isEqualTo(publishedAt)
        assertThat(eventually { recordsFor(paymentId, expected = 2) })
            .`as`("exactly two records on the topic for this payment, not four")
            .hasSize(2)
    }

    @Test
    fun `the relay drains the backlog`() {
        accountServiceAccepts()
        repeat(5) { pay(key = "outbox-drain-$it", amount = 1_000L + it) }

        eventually(Duration.ofSeconds(20)) {
            assertThat(outbox.countByPublishedAtIsNull())
                .`as`("nothing is left unpublished once the relay has caught up")
                .isZero()
        }
    }

    private fun rowsFor(paymentId: UUID, expected: Int) =
        outbox.findByAggregateIdOrderByOccurredAt(paymentId).also {
            assertThat(it).hasSize(expected)
            assertThat(it).allSatisfy { row -> assertThat(row.publishedAt).isNotNull() }
        }

    /** Consumes cumulatively -- other tests share the topic, so records are filtered by key. */
    private fun recordsFor(paymentId: UUID, expected: Int): List<ConsumerRecord<String, String>> {
        consumer.poll(Duration.ofMillis(500)).forEach { consumed += it }
        val mine = consumed.filter { it.key() == paymentId.toString() }
        assertThat(mine).hasSize(expected)
        return mine
    }

    private fun header(record: ConsumerRecord<String, String>, name: String): String =
        String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8)

    private fun accountServiceAccepts() {
        accountService.stubFor(
            post(urlEqualTo("/internal/transfers")).willReturn(
                aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                    .withBody("""{"replayed":false}"""),
            ),
        )
    }

    private fun pay(
        key: String,
        amount: Long,
        expectedStatus: HttpStatus = HttpStatus.CREATED,
    ): UUID {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set("Idempotency-Key", key)
        }
        val body = mapOf(
            "fromAccountId" to 1,
            "toAccountId" to 2,
            "amount" to amount,
            "currency" to "EUR",
        )
        val response = rest.exchange("/payments", HttpMethod.POST, HttpEntity(body, headers), JsonNode::class.java)
        assertThat(response.statusCode).isEqualTo(expectedStatus)
        return UUID.fromString(response.body!!["id"].asText())
    }

    /** Small poll-until-true helper; not worth an Awaitility dependency for one file. */
    private fun <T> eventually(timeout: Duration = Duration.ofSeconds(10), block: () -> T): T {
        val deadline = System.nanoTime() + timeout.toNanos()
        var last: AssertionError? = null
        while (System.nanoTime() < deadline) {
            try {
                return block()
            } catch (e: AssertionError) {
                last = e
                Thread.sleep(50)
            }
        }
        throw AssertionError("Condition not met within $timeout", last)
    }
}
