package dev.gokce.payments.payment.api

import com.fasterxml.jackson.databind.exc.MismatchedInputException
import dev.gokce.payments.payment.domain.IdempotencyKeyReusedException
import dev.gokce.payments.payment.domain.IllegalPaymentTransitionException
import dev.gokce.payments.payment.domain.PaymentNotFoundException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import java.net.URI

/** Same contract as account-service: every error leaves as RFC 7807 `application/problem+json`. */
@RestControllerAdvice
class ApiExceptionHandler : ResponseEntityExceptionHandler() {

    @ExceptionHandler(PaymentNotFoundException::class)
    fun onPaymentNotFound(e: PaymentNotFoundException) = problem(
        status = HttpStatus.NOT_FOUND,
        type = "payment-not-found",
        title = "Payment not found",
        detail = e.message,
    )

    /** Same key, different body. 422 per the API contract, never a silent replay of the old one. */
    @ExceptionHandler(IdempotencyKeyReusedException::class)
    fun onIdempotencyKeyReused(e: IdempotencyKeyReusedException) = problem(
        status = HttpStatus.UNPROCESSABLE_ENTITY,
        type = "idempotency-key-reused",
        title = "Idempotency key reused",
        detail = e.message,
        properties = mapOf("idempotencyKey" to e.idempotencyKey),
    )

    @ExceptionHandler(IllegalPaymentTransitionException::class)
    fun onIllegalTransition(e: IllegalPaymentTransitionException) = problem(
        status = HttpStatus.CONFLICT,
        type = "illegal-payment-transition",
        title = "Illegal payment transition",
        detail = e.message,
    )

    override fun handleMethodArgumentNotValid(
        ex: MethodArgumentNotValidException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any> {
        val errors = ex.bindingResult.fieldErrors.map {
            mapOf("field" to it.field, "message" to (it.defaultMessage ?: "is invalid"))
        }
        return ResponseEntity.badRequest().body(
            problemDetail(
                status = HttpStatus.BAD_REQUEST,
                type = "validation-failed",
                title = "Validation failed",
                detail = "The request body failed validation",
                properties = mapOf("errors" to errors),
            ),
        )
    }

    override fun handleHttpMessageNotReadable(
        ex: HttpMessageNotReadableException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any> {
        val path = (ex.cause as? MismatchedInputException)?.path
            ?.joinToString(".") { it.fieldName ?: "[${it.index}]" }
            ?.takeIf { it.isNotEmpty() }
        return ResponseEntity.badRequest().body(
            problemDetail(
                status = HttpStatus.BAD_REQUEST,
                type = "malformed-request-body",
                title = "Malformed request body",
                detail = path?.let { "Field '$it' is missing or has the wrong type" }
                    ?: "The request body could not be parsed",
            ),
        )
    }

    private fun problem(
        status: HttpStatus,
        type: String,
        title: String,
        detail: String?,
        properties: Map<String, Any> = emptyMap(),
    ): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(status).body(problemDetail(status, type, title, detail, properties))

    private fun problemDetail(
        status: HttpStatus,
        type: String,
        title: String,
        detail: String?,
        properties: Map<String, Any> = emptyMap(),
    ): ProblemDetail = ProblemDetail.forStatus(status).apply {
        this.type = URI.create("https://payments-ledger.gokce.dev/problems/$type")
        this.title = title
        this.detail = detail
        properties.forEach { (key, value) -> setProperty(key, value) }
    }
}
