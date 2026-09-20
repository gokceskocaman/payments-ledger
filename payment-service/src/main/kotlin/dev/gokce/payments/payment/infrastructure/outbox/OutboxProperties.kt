package dev.gokce.payments.payment.infrastructure.outbox

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "outbox")
data class OutboxProperties(
    val topic: String = "payments.events",
    /**
     * How long an event can sit unpublished. This is the latency the outbox costs: events are never
     * lost, but they are not instantaneous either.
     */
    val pollInterval: Duration = Duration.ofSeconds(1),
    /** Bounded so one relay tick holds its row locks and its transaction briefly. */
    val batchSize: Int = 100,
    val topicPartitions: Int = 3,
    /** 1 for the single-broker dev cluster; a real deployment wants 3. */
    val topicReplicas: Int = 1,
)
