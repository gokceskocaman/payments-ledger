package dev.gokce.payments.account.infrastructure.messaging

import com.fasterxml.jackson.core.JacksonException
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.common.TopicPartition
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.KafkaException
import org.springframework.kafka.config.TopicBuilder
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.util.backoff.ExponentialBackOff

@Configuration
@EnableConfigurationProperties(PaymentEventsProperties::class)
class KafkaConsumerConfiguration {

    /**
     * This service owns its dead-letter topic; the source topic belongs to payment-service. Declared
     * explicitly because auto-creation is off on the broker.
     */
    @Bean
    fun paymentEventsDeadLetterTopic(properties: PaymentEventsProperties): NewTopic = TopicBuilder
        .name(properties.dltTopic)
        .partitions(properties.dltPartitions)
        .replicas(properties.dltReplicas)
        .build()

    /**
     * Retries in the consumer, then a dead-letter topic.
     *
     * Retrying matters because a listener can fail for reasons that pass: a database failover, a lock
     * held a moment too long, a blip in the network. But retrying *in place* blocks the partition, so
     * the attempts are few and the backoff is capped -- one bad record must not hold up everything
     * queued behind it. When the attempts run out the record goes to the DLT and the partition moves
     * on, turning an outage into something to inspect later.
     *
     * A malformed payload is classified as non-retryable and skips straight to the DLT: no amount of
     * waiting will make invalid JSON parse.
     */
    @Bean
    fun paymentEventErrorHandler(
        kafkaTemplate: KafkaTemplate<String, String>,
        properties: PaymentEventsProperties,
    ): DefaultErrorHandler {
        val recoverer = DeadLetterPublishingRecoverer(kafkaTemplate) { _, _ ->
            // -1 lets the producer choose the partition. Pinning the source partition would break as
            // soon as the DLT had fewer partitions than the topic it serves.
            TopicPartition(properties.dltTopic, -1)
        }

        val backOff = ExponentialBackOff().apply {
            initialInterval = properties.retryInitialInterval.toMillis()
            multiplier = properties.retryMultiplier
            maxInterval = properties.retryMaxInterval.toMillis()
            // Bound the attempts, not the elapsed time: "try three more times" is a decision you can
            // reason about, "keep trying for 30 seconds" depends on how long each attempt took.
            maxAttempts = properties.maxRetries
        }

        return DefaultErrorHandler(recoverer, backOff).apply {
            addNotRetryableExceptions(JacksonException::class.java, IllegalArgumentException::class.java)
            setLogLevel(KafkaException.Level.WARN)
        }
    }
}
