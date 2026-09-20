package dev.gokce.payments.account.api

import com.fasterxml.jackson.databind.exc.MismatchedInputException
import dev.gokce.payments.account.domain.AccountNotFoundException
import dev.gokce.payments.account.domain.CurrencyMismatchException
import dev.gokce.payments.account.domain.CurrencyNotFundableException
import dev.gokce.payments.account.domain.InsufficientFundsException
import dev.gokce.payments.account.domain.SameAccountTransferException
import dev.gokce.payments.account.domain.SystemAccountNotAllowedException
import dev.gokce.payments.account.domain.TransferConflictException
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

/**
 * Every error leaves this service as an RFC 7807 `application/problem+json` document.
 *
 * Extending [ResponseEntityExceptionHandler] rather than setting
 * `spring.mvc.problemdetails.enabled` keeps one mechanism in play: the framework's own exceptions
 * (unsupported method, unreadable body) and our domain exceptions are shaped in the same place.
 */
@RestControllerAdvice
class ApiExceptionHandler : ResponseEntityExceptionHandler() {

    @ExceptionHandler(AccountNotFoundException::class)
    fun onAccountNotFound(e: AccountNotFoundException) = problem(
        status = HttpStatus.NOT_FOUND,
        type = "account-not-found",
        title = "Account not found",
        detail = e.message,
    )

    @ExceptionHandler(InsufficientFundsException::class)
    fun onInsufficientFunds(e: InsufficientFundsException) = problem(
        status = HttpStatus.UNPROCESSABLE_ENTITY,
        type = "insufficient-funds",
        title = "Insufficient funds",
        detail = e.message,
        properties = mapOf("balance" to e.balance, "requested" to e.requested),
    )

    @ExceptionHandler(CurrencyMismatchException::class)
    fun onCurrencyMismatch(e: CurrencyMismatchException) = problem(
        status = HttpStatus.UNPROCESSABLE_ENTITY,
        type = "currency-mismatch",
        title = "Currency mismatch",
        detail = e.message,
        properties = mapOf("expected" to e.expected, "actual" to e.actual),
    )

    @ExceptionHandler(CurrencyNotFundableException::class)
    fun onCurrencyNotFundable(e: CurrencyNotFundableException) = problem(
        status = HttpStatus.UNPROCESSABLE_ENTITY,
        type = "currency-not-fundable",
        title = "Currency not fundable",
        detail = e.message,
    )

    @ExceptionHandler(SystemAccountNotAllowedException::class)
    fun onSystemAccountNotAllowed(e: SystemAccountNotAllowedException) = problem(
        status = HttpStatus.UNPROCESSABLE_ENTITY,
        type = "system-account-not-allowed",
        title = "System account not allowed",
        detail = e.message,
    )

    @ExceptionHandler(SameAccountTransferException::class)
    fun onSameAccountTransfer(e: SameAccountTransferException) = problem(
        status = HttpStatus.UNPROCESSABLE_ENTITY,
        type = "same-account-transfer",
        title = "Same account transfer",
        detail = e.message,
    )

    /** Same transferId, different details -- the idempotency key has been reused. */
    @ExceptionHandler(TransferConflictException::class)
    fun onTransferConflict(e: TransferConflictException) = problem(
        status = HttpStatus.UNPROCESSABLE_ENTITY,
        type = "transfer-conflict",
        title = "Transfer conflict",
        detail = e.message,
        properties = mapOf("transferId" to e.transferId.toString()),
    )

    /** Field-level validation failures, listed so a client can fix them all in one round trip. */
    override fun handleMethodArgumentNotValid(
        ex: MethodArgumentNotValidException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any> {
        val errors = ex.bindingResult.fieldErrors.map {
            mapOf("field" to it.field, "message" to (it.defaultMessage ?: "is invalid"))
        }
        val body = problemDetail(
            status = HttpStatus.BAD_REQUEST,
            type = "validation-failed",
            title = "Validation failed",
            detail = "The request body failed validation",
            properties = mapOf("errors" to errors),
        )
        return ResponseEntity.badRequest().body(body)
    }

    /**
     * A missing or wrongly typed JSON field never reaches Bean Validation -- Kotlin's non-null
     * types make Jackson reject it first. Naming the offending field keeps that error as useful as
     * a validation error.
     */
    override fun handleHttpMessageNotReadable(
        ex: HttpMessageNotReadableException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any> {
        val path = (ex.cause as? MismatchedInputException)?.path
            ?.joinToString(".") { it.fieldName ?: "[${it.index}]" }
            ?.takeIf { it.isNotEmpty() }
        val body = problemDetail(
            status = HttpStatus.BAD_REQUEST,
            type = "malformed-request-body",
            title = "Malformed request body",
            detail = path?.let { "Field '$it' is missing or has the wrong type" }
                ?: "The request body could not be parsed",
        )
        return ResponseEntity.badRequest().body(body)
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
