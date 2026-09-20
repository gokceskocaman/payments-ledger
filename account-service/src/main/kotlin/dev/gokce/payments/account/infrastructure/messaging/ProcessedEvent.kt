package dev.gokce.payments.account.infrastructure.messaging

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Read model for [ProcessedEventRepository]. Rows are written by an `ON CONFLICT DO NOTHING` insert
 * rather than through this entity, because the point of the table is that the *database* decides
 * whether an event is new.
 */
@Entity
@Table(name = "processed_events")
class ProcessedEvent(
    @Id
    @Column(name = "event_id", nullable = false)
    val eventId: UUID,

    @Column(name = "event_type", nullable = false, length = 64)
    val eventType: String,

    @Column(name = "payment_id", nullable = false)
    val paymentId: UUID,

    @Column(nullable = false, length = 255)
    val topic: String,

    @Column(name = "partition_number", nullable = false)
    val partitionNumber: Int,

    @Column(name = "kafka_offset", nullable = false)
    val kafkaOffset: Long,

    @Column(nullable = false)
    val payload: String,

    @Column(name = "processed_at", nullable = false)
    val processedAt: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS),
)
