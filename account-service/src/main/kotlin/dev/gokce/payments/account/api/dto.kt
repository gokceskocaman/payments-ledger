package dev.gokce.payments.account.api

import dev.gokce.payments.account.application.PostedTransfer
import dev.gokce.payments.account.domain.Account
import dev.gokce.payments.account.domain.Direction
import dev.gokce.payments.account.domain.LedgerEntry
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import org.springframework.data.domain.Page
import java.time.Instant
import java.util.UUID

/** ISO 4217 alphabetic code. Kept as a constant so every money-carrying DTO validates it alike. */
const val CURRENCY_PATTERN = "^[A-Z]{3}$"
private const val CURRENCY_MESSAGE = "must be a 3-letter uppercase ISO 4217 code"

data class CreateAccountRequest(
    @field:NotBlank
    @field:Size(max = 200)
    val ownerName: String,

    @field:Pattern(regexp = CURRENCY_PATTERN, message = CURRENCY_MESSAGE)
    val currency: String,
)

data class DepositRequest(
    /** Client-supplied, so a retried deposit is recognised instead of posted twice. */
    val transferId: UUID,

    /** Minor units, e.g. 25000 for EUR 250.00. Never a decimal. */
    @field:Positive
    val amount: Long,

    @field:Pattern(regexp = CURRENCY_PATTERN, message = CURRENCY_MESSAGE)
    val currency: String,
)

data class TransferRequest(
    /** Supplied by the caller, so a retry after a timeout is recognised rather than posted twice. */
    val transferId: UUID,

    @field:Positive
    val fromAccountId: Long,

    @field:Positive
    val toAccountId: Long,

    @field:Positive
    val amount: Long,

    @field:Pattern(regexp = CURRENCY_PATTERN, message = CURRENCY_MESSAGE)
    val currency: String,
)

data class TransferResponse(
    val transferId: UUID,
    val fromAccountId: Long,
    val toAccountId: Long,
    val amount: Long,
    val currency: String,
    val postedAt: Instant,
    /** False when this call posted the movement, true when it replayed an earlier one. */
    val replayed: Boolean,
) {
    companion object {
        fun from(transfer: PostedTransfer) = TransferResponse(
            transferId = transfer.transferId,
            fromAccountId = transfer.fromAccountId,
            toAccountId = transfer.toAccountId,
            amount = transfer.amount,
            currency = transfer.currency,
            postedAt = transfer.postedAt,
            replayed = transfer.replayed,
        )
    }
}

data class AccountResponse(
    val id: Long,
    val ownerName: String,
    val currency: String,
    val balance: Long,
) {
    companion object {
        fun from(account: Account) = AccountResponse(
            id = checkNotNull(account.id) { "Account has not been persisted" },
            ownerName = account.ownerName,
            currency = account.currency,
            balance = account.balance,
        )
    }
}

data class LedgerEntryResponse(
    val id: Long,
    val transferId: UUID,
    val direction: Direction,
    val amount: Long,
    val currency: String,
    val createdAt: Instant,
) {
    companion object {
        fun from(entry: LedgerEntry) = LedgerEntryResponse(
            id = checkNotNull(entry.id) { "Ledger entry has not been persisted" },
            transferId = entry.transferId,
            direction = entry.direction,
            amount = entry.amount,
            currency = entry.currency,
            createdAt = entry.createdAt,
        )
    }
}

/**
 * Explicit page envelope instead of serialising Spring's `Page`: `PageImpl`'s JSON shape is an
 * implementation detail Spring itself warns about, and this keeps the API contract ours.
 */
data class PageResponse<T>(
    val content: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
) {
    companion object {
        fun <E, T> of(page: Page<E>, map: (E) -> T) = PageResponse(
            content = page.content.map(map),
            page = page.number,
            size = page.size,
            totalElements = page.totalElements,
            totalPages = page.totalPages,
        )
    }
}
