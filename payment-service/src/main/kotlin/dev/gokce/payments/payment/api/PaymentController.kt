package dev.gokce.payments.payment.api

import dev.gokce.payments.payment.application.PaymentService
import dev.gokce.payments.payment.application.SubmitPaymentCommand
import dev.gokce.payments.payment.application.SubmittedPayment
import dev.gokce.payments.payment.domain.PaymentStatus
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.util.UUID

@RestController
@RequestMapping("/payments")
@Validated
class PaymentController(private val paymentService: PaymentService) {

    /**
     * The `Idempotency-Key` header is required, not optional. A payments API that lets a caller omit
     * it invites exactly the duplicate charge the header exists to prevent.
     *
     * Status codes carry the outcome's certainty, and the body always carries `status`:
     * - **201** this request created the payment and it reached a terminal state (COMPLETED or FAILED);
     * - **202** this request created the payment but its outcome is *not yet known* -- poll `GET /payments/{id}`
     *   or simply retry with the same key;
     * - **200** the payment already existed; this is the stored outcome.
     */
    @Operation(
        summary = "Submit a payment",
        description = "Idempotent on the Idempotency-Key header. 201 when this request created a " +
            "resolved payment, 202 when the outcome is not yet known, 200 when the payment already " +
            "existed and this is its stored outcome.",
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "201",
            description = "Created and resolved (COMPLETED or FAILED)",
            content = [
                Content(
                    examples = [
                        ExampleObject(
                            name = "completed",
                            value = """{"id":"f48631d1-f92d-4f5b-b657-fe882ab7fdc9","status":"COMPLETED",
 "fromAccountId":2,"toAccountId":3,"amount":12000,"currency":"EUR",
 "failureReason":null,"createdAt":"2026-09-20T09:26:32.748411Z"}""",
                        ),
                        ExampleObject(
                            name = "failed",
                            description = "account-service refused it definitively; nothing moved",
                            value = """{"id":"489c10d7-a471-4a32-87c1-a5e4fa26d7ee","status":"FAILED",
 "fromAccountId":2,"toAccountId":3,"amount":9999999,"currency":"EUR",
 "failureReason":"Account 2 holds 38000, which cannot cover a debit of 9999999",
 "createdAt":"2026-09-20T09:26:33.007567Z"}""",
                        ),
                    ],
                ),
            ],
        ),
        ApiResponse(
            responseCode = "202",
            description = "Accepted, outcome unknown. The transfer may or may not have been posted: " +
                "retry with the same key, or poll GET /payments/{id}.",
            content = [
                Content(
                    examples = [
                        ExampleObject(
                            value = """{"id":"9f2c1b7e-3d4a-4f11-9c22-aabbccddeeff","status":"PENDING",
 "fromAccountId":2,"toAccountId":3,"amount":12000,"currency":"EUR",
 "failureReason":null,"createdAt":"2026-09-20T09:26:32.748411Z"}""",
                        ),
                    ],
                ),
            ],
        ),
        ApiResponse(responseCode = "200", description = "The payment already existed; this is its stored outcome"),
        ApiResponse(
            responseCode = "422",
            description = "The Idempotency-Key was already used for a different payment",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    examples = [
                        ExampleObject(
                            value = """{"type":"https://payments-ledger.gokce.dev/problems/idempotency-key-reused",
 "title":"Idempotency key reused","status":422,
 "detail":"Idempotency-Key 'order-4711' was already used for a different payment",
 "idempotencyKey":"order-4711"}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    @PostMapping
    fun create(
        @Parameter(
            description = "Caller-generated key that makes this request safe to retry",
            example = "order-4711",
            required = true,
        )
        @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 255) idempotencyKey: String,
        @Valid @RequestBody request: CreatePaymentRequest,
    ): ResponseEntity<PaymentResponse> {
        val submitted = paymentService.submit(
            SubmitPaymentCommand(
                idempotencyKey = idempotencyKey,
                fromAccountId = request.fromAccountId,
                toAccountId = request.toAccountId,
                amount = request.amount,
                currency = request.currency,
            ),
        )
        val body = PaymentResponse.from(submitted.payment)
        return ResponseEntity.status(statusFor(submitted))
            .location(URI.create("/payments/${body.id}"))
            .body(body)
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): PaymentResponse =
        PaymentResponse.from(paymentService.get(id))

    private fun statusFor(submitted: SubmittedPayment): HttpStatus = when {
        submitted.replayed -> HttpStatus.OK
        submitted.payment.status == PaymentStatus.PENDING -> HttpStatus.ACCEPTED
        else -> HttpStatus.CREATED
    }
}
