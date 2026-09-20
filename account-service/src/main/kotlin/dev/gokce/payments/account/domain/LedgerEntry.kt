package dev.gokce.payments.account.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * One side of one movement of money: append-only, never updated or deleted.
 *
 * [transferId] groups the two entries of a movement (DEBIT source, CREDIT destination), which is
 * what makes a movement replay-safe: a unique constraint on (transfer_id, account_id, direction)
 * rejects a second attempt to post the same side twice.
 *
 * The account is referenced by id rather than a `@ManyToOne` association on purpose -- the ledger
 * is read as a flat stream, and an association would add a join or a lazy-loading trap to every
 * page of entries. The foreign key still exists in the database.
 */
@Entity
@Table(name = "ledger_entries")
class LedgerEntry(
    @Column(name = "account_id", nullable = false)
    val accountId: Long,

    @Column(name = "transfer_id", nullable = false)
    val transferId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 6)
    val direction: Direction,

    /** Always positive, in minor units. The sign of the movement lives in [direction]. */
    @Column(nullable = false)
    val amount: Long,

    @Column(nullable = false, length = 3)
    val currency: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        private set

    /** Microseconds: the precision `timestamptz` keeps, so the API never returns one it cannot store. */
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS)
}
