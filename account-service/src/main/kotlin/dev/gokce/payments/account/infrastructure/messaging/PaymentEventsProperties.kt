package dev.gokce.payments.account.infrastructure.messaging

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "payment-events")
data class PaymentEventsProperties(
    /** Owned and created by payment-service; this service only subscribes. */
    val topic: String = "payments.events",
    val groupId: String = "account-service",
    /** Where a record goes once retries are exhausted, so the partition can move on. */
    val dltTopic: String = "payments.events.DLT",
    val dltPartitions: Int = 3,
    val dltReplicas: Int = 1,
    /** Deliveries after the first. Three is enough to ride out a failover, not enough to stall. */
    val maxRetries: Int = 3,
    val retryInitialInterval: Duration = Duration.ofMillis(200),
    val retryMultiplier: Double = 2.0,
    val retryMaxInterval: Duration = Duration.ofSeconds(2),
)
