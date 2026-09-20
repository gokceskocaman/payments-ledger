package dev.gokce.payments.payment.api

import dev.gokce.payments.payment.application.PaymentService
import dev.gokce.payments.payment.application.SubmitPaymentCommand
import dev.gokce.payments.payment.application.SubmittedPayment
import dev.gokce.payments.payment.domain.PaymentStatus
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
    @PostMapping
    fun create(
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
