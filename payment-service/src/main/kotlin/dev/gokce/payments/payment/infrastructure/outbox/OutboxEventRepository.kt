package dev.gokce.payments.payment.infrastructure.outbox

import dev.gokce.payments.payment.domain.OutboxEvent
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface OutboxEventRepository : JpaRepository<OutboxEvent, UUID> {

    /**
     * The relay's claim query.
     *
     * `FOR UPDATE SKIP LOCKED` is what lets more than one instance of this service relay at the same
     * time: each locks a disjoint batch and steps straight over rows another instance is already
     * holding, instead of queueing behind them. `ORDER BY occurred_at` keeps events going out in the
     * order they happened.
     *
     * Written as SQL rather than a `@Lock` annotation because skip-locked is expressible in Spring
     * Data only through a magic lock-timeout value of -2, which is considerably less obvious than
     * the three words it stands for.
     */
    @Query(
        value = """
            select * from outbox_events
            where published_at is null
            order by occurred_at
            limit :limit
            for update skip locked
        """,
        nativeQuery = true,
    )
    fun claimUnpublished(@Param("limit") limit: Int): List<OutboxEvent>

    fun findByAggregateIdOrderByOccurredAt(aggregateId: UUID): List<OutboxEvent>

    fun countByPublishedAtIsNull(): Long
}
