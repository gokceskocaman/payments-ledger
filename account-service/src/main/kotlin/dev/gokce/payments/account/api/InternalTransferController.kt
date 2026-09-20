package dev.gokce.payments.account.api

import dev.gokce.payments.account.application.AccountService
import dev.gokce.payments.account.application.TransferCommand
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Service-to-service API, called by payment-service. Kept under /internal to mark it as not part of
 * the customer-facing surface: it trusts its caller to have done authorisation and to own the
 * transferId it supplies.
 */
@RestController
@RequestMapping("/internal/transfers")
class InternalTransferController(private val accountService: AccountService) {

    /**
     * 200 rather than 201 on purpose. The endpoint is idempotent, so a retry after a network timeout
     * has to be indistinguishable from the original call -- a caller that cannot tell whether its
     * first attempt arrived must be able to send it again and get the same answer. `replayed` is
     * there for the callers that do care.
     */
    @PostMapping
    fun transfer(@Valid @RequestBody request: TransferRequest): TransferResponse =
        TransferResponse.from(
            accountService.transfer(
                TransferCommand(
                    transferId = request.transferId,
                    fromAccountId = request.fromAccountId,
                    toAccountId = request.toAccountId,
                    amount = request.amount,
                    currency = request.currency,
                ),
            ),
        )
}
