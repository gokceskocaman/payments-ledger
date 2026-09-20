package dev.gokce.payments.account.infrastructure.messaging

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface ProcessedEventRepository : JpaRepository<ProcessedEvent, UUID> {

    /**
     * Claims an event id, returning 1 if this delivery is the first and 0 if it is a duplicate.
     *
     * `ON CONFLICT DO NOTHING` rather than "check, then insert": the check-then-insert version has a
     * window between the two statements, and while a partition is normally consumed by one thread,
     * a rebalance can briefly overlap two. This is one atomic statement, so the race cannot happen --
     * and unlike catching a constraint violation, it does not poison the transaction.
     */
    @Modifying
    @Query(
        value = """
            insert into processed_events
                (event_id, event_type, payment_id, topic, partition_number, kafka_offset, payload, processed_at)
            values
                (:eventId, :eventType, :paymentId, :topic, :partitionNumber, :kafkaOffset, :payload, now())
            on conflict (event_id) do nothing
        """,
        nativeQuery = true,
    )
    fun insertIfAbsent(
        @Param("eventId") eventId: UUID,
        @Param("eventType") eventType: String,
        @Param("paymentId") paymentId: UUID,
        @Param("topic") topic: String,
        @Param("partitionNumber") partitionNumber: Int,
        @Param("kafkaOffset") kafkaOffset: Long,
        @Param("payload") payload: String,
    ): Int

    fun countByPaymentId(paymentId: UUID): Long
}
