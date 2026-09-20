package dev.gokce.payments.account.application

import java.time.Instant
import java.util.UUID

/** What payment-service asks for. [transferId] is its idempotency key. */
data class TransferCommand(
    val transferId: UUID,
    val fromAccountId: Long,
    val toAccountId: Long,
    val amount: Long,
    val currency: String,
)

/**
 * The movement as the ledger holds it. [postedAt] is the time the entries were *first* written, so a
 * replay is byte-for-byte the original answer; [replayed] tells the caller which of the two it got.
 */
data class PostedTransfer(
    val transferId: UUID,
    val fromAccountId: Long,
    val toAccountId: Long,
    val amount: Long,
    val currency: String,
    val postedAt: Instant,
    val replayed: Boolean,
)
