package dev.gokce.payments.payment.infrastructure.accounts

import com.fasterxml.jackson.databind.JsonNode
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import java.util.UUID

@Component
class AccountServiceClient(private val restClient: RestClient) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Posts a transfer. [transferId] is the payment's own id, so calling this twice for one payment
     * is safe: account-service is idempotent on it and will replay the first movement rather than
     * making a second one.
     */
    fun transfer(
        transferId: UUID,
        fromAccountId: Long,
        toAccountId: Long,
        amount: Long,
        currency: String,
    ): TransferOutcome = try {
        val response = restClient.post()
            .uri("/internal/transfers")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                mapOf(
                    "transferId" to transferId,
                    "fromAccountId" to fromAccountId,
                    "toAccountId" to toAccountId,
                    "amount" to amount,
                    "currency" to currency,
                ),
            )
            // Default error handling throws on any non-2xx, which would collapse "refused" and
            // "no idea" into one exception type. The status is the information we need most.
            .retrieve()
            .onStatus({ true }) { _, _ -> }
            .toEntity(JsonNode::class.java)

        outcomeOf(response.statusCode, response.body, transferId)
    } catch (e: ResourceAccessException) {
        // Read timeout, connect timeout, connection reset: the request may well have been executed.
        log.warn("Transfer {} gave no answer: {}", transferId, e.message)
        TransferOutcome.Indeterminate(e.message ?: "no response from account-service")
    } catch (e: Exception) {
        // Deliberately broad. This method's contract is to return an outcome, never to throw: a
        // leaked exception becomes a 500, and a caller who gets a 500 cannot tell whether their
        // money moved. A read timeout on the JDK HTTP client, for instance, surfaces as a
        // CancellationException rather than the IOException Spring usually translates.
        log.warn("Transfer {} failed unexpectedly ({}): {}", transferId, e.javaClass.simpleName, e.message)
        TransferOutcome.Indeterminate("${e.javaClass.simpleName}: ${e.message}")
    }

    private fun outcomeOf(status: HttpStatusCode, body: JsonNode?, transferId: UUID): TransferOutcome =
        when {
            status.is2xxSuccessful -> TransferOutcome.Posted

            // Only the statuses account-service actually uses to say "I decided not to". Any other
            // 4xx is far more likely to be a misrouted or malformed request than a real refusal,
            // and treating that as FAILED would tell a customer their payment was declined when
            // nobody ever looked at it.
            status in DEFINITIVE_REFUSALS -> TransferOutcome.Refused(describe(body, status))

            else -> {
                log.warn("Transfer {} answered {}, treating as unresolved", transferId, status)
                TransferOutcome.Indeterminate(describe(body, status))
            }
        }

    /** account-service answers with RFC 7807, so the useful text is in `detail`. */
    private fun describe(body: JsonNode?, status: HttpStatusCode): String =
        body?.get("detail")?.asText()
            ?: body?.get("title")?.asText()
            ?: "account-service responded $status"

    private companion object {
        val DEFINITIVE_REFUSALS = setOf(
            HttpStatus.BAD_REQUEST,
            HttpStatus.NOT_FOUND,
            HttpStatus.UNPROCESSABLE_ENTITY,
        )
    }
}
