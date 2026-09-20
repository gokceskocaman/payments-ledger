package dev.gokce.payments.account.api

import dev.gokce.payments.account.application.AccountService
import dev.gokce.payments.account.application.TransferCommand
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
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
    @Operation(
        summary = "Move money between two accounts",
        description = "Idempotent on transferId. Writes one DEBIT and one CREDIT entry in a single " +
            "transaction, taking row locks in ascending account id order. Requires the " +
            "`ledger:internal` scope.",
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "Posted, or replayed if this transferId was already posted",
            content = [
                Content(
                    examples = [
                        ExampleObject(
                            name = "posted",
                            value = """{"transferId":"6f1e2d3c-4b5a-4968-8877-665544332211",
 "fromAccountId":2,"toAccountId":3,"amount":12000,"currency":"EUR",
 "postedAt":"2026-09-20T09:26:32.525148Z","replayed":false}""",
                        ),
                        ExampleObject(
                            name = "replayed",
                            description = "A retry: same body, and postedAt is the original posting time",
                            value = """{"transferId":"6f1e2d3c-4b5a-4968-8877-665544332211",
 "fromAccountId":2,"toAccountId":3,"amount":12000,"currency":"EUR",
 "postedAt":"2026-09-20T09:26:32.525148Z","replayed":true}""",
                        ),
                    ],
                ),
            ],
        ),
        ApiResponse(
            responseCode = "422",
            description = "Refused: insufficient funds, currency mismatch, or a reused transferId",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    examples = [
                        ExampleObject(
                            name = "insufficient funds",
                            value = """{"type":"https://payments-ledger.gokce.dev/problems/insufficient-funds",
 "title":"Insufficient funds","status":422,
 "detail":"Account 2 holds 7000, which cannot cover a debit of 999999",
 "balance":7000,"requested":999999}""",
                        ),
                    ],
                ),
            ],
        ),
    )
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
