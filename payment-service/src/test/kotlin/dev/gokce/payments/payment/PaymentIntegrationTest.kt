package dev.gokce.payments.payment

import com.fasterxml.jackson.databind.JsonNode
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import dev.gokce.payments.payment.domain.PaymentStatus
import dev.gokce.payments.payment.infrastructure.PaymentRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

class PaymentIntegrationTest @Autowired constructor(
    private val rest: TestRestTemplate,
    private val payments: PaymentRepository,
    private val jdbc: JdbcTemplate,
) : PaymentServiceTestBase() {

    @Test
    fun `a payment that account-service posts is completed`() {
        accountServiceAccepts()

        val response = pay(key = "key-completed", amount = 25_000)

        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        val body = response.body!!
        assertThat(body["status"].asText()).isEqualTo("COMPLETED")
        assertThat(body["failureReason"].isNull).isTrue()
        assertThat(response.headers.location.toString()).isEqualTo("/payments/${body["id"].asText()}")

        // The payment id is the transferId: that identity is what makes a retry safe.
        accountService.verify(
            postRequestedFor(urlEqualTo("/internal/transfers"))
                .withRequestBody(matchingJsonPath("$.transferId", equalToValue(body["id"].asText())))
                .withRequestBody(matchingJsonPath("$.amount", equalToValue("25000"))),
        )
    }

    @Test
    fun `the same key and body returns the stored payment without calling account-service again`() {
        accountServiceAccepts()

        val first = pay(key = "key-replayed", amount = 4_000)
        val second = pay(key = "key-replayed", amount = 4_000)

        assertThat(first.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(second.statusCode)
            .`as`("the payment already existed, so nothing was created")
            .isEqualTo(HttpStatus.OK)
        assertThat(second.body!!["id"].asText())
            .`as`("a retry must name the same payment")
            .isEqualTo(first.body!!["id"].asText())
        assertThat(second.body!!["status"].asText()).isEqualTo("COMPLETED")

        assertThat(accountServiceRequests())
            .`as`("a resolved payment must never be attempted a second time")
            .isEqualTo(1)
    }

    @Test
    fun `the same key with a different body is refused with 422`() {
        accountServiceAccepts()

        val first = pay(key = "key-conflict", amount = 1_000)
        val conflicting = pay(key = "key-conflict", amount = 9_999)

        assertThat(first.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(conflicting.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
        assertThat(conflicting.body!!["title"].asText()).isEqualTo("Idempotency key reused")
        assertThat(accountServiceRequests())
            .`as`("a conflicting request must not reach account-service at all")
            .isEqualTo(1)
    }

    @Test
    fun `a refusal from account-service fails the payment with a reason`() {
        accountService.stubFor(
            post(urlEqualTo("/internal/transfers")).willReturn(
                aResponse()
                    .withStatus(422)
                    .withHeader("Content-Type", "application/problem+json")
                    .withBody(
                        """{"type":"https://payments-ledger.gokce.dev/problems/insufficient-funds",
                            "title":"Insufficient funds","status":422,
                            "detail":"Account 1 holds 500, which cannot cover a debit of 25000"}""",
                    ),
            ),
        )

        val response = pay(key = "key-refused", amount = 25_000)

        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        val body = response.body!!
        assertThat(body["status"].asText()).isEqualTo("FAILED")
        assertThat(body["failureReason"].asText()).contains("cannot cover a debit of 25000")

        // FAILED is terminal and definitive, so a retry answers from storage.
        val retry = pay(key = "key-refused", amount = 25_000)
        assertThat(retry.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(retry.body!!["status"].asText()).isEqualTo("FAILED")
        assertThat(accountServiceRequests()).isEqualTo(1)
    }

    @Test
    fun `a timeout leaves the payment pending rather than failed`() {
        accountService.stubFor(
            post(urlEqualTo("/internal/transfers")).willReturn(
                // Longer than the 300ms read timeout: the client gives up before the answer arrives,
                // exactly as it would if account-service had posted the transfer and then stalled.
                aResponse().withFixedDelay(2_000).withStatus(200).withBody("{}"),
            ),
        )

        val response = pay(key = "key-timeout", amount = 7_000)

        assertThat(response.statusCode)
            .`as`("202: submitted, outcome not yet known")
            .isEqualTo(HttpStatus.ACCEPTED)
        val body = response.body!!
        assertThat(body["status"].asText())
            .`as`("a timeout is not evidence that the money did not move, so FAILED would be a lie")
            .isEqualTo("PENDING")
        assertThat(body["failureReason"].isNull).isTrue()

        val fetched = rest.getForEntity("/payments/${body["id"].asText()}", JsonNode::class.java)
        assertThat(fetched.body!!["status"].asText()).isEqualTo("PENDING")
    }

    @Test
    fun `retrying after a timeout resolves the payment without moving the money twice`() {
        accountService.stubFor(
            post(urlEqualTo("/internal/transfers"))
                .willReturn(aResponse().withFixedDelay(2_000).withStatus(200).withBody("{}")),
        )

        val timedOut = pay(key = "key-recovered", amount = 3_000)
        assertThat(timedOut.statusCode).isEqualTo(HttpStatus.ACCEPTED)
        val paymentId = timedOut.body!!["id"].asText()

        // The money had in fact moved; account-service now answers, and because the transferId is
        // unchanged it replays that same movement instead of making a second one.
        accountService.resetAll()
        accountService.stubFor(
            post(urlEqualTo("/internal/transfers")).willReturn(
                aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                    .withBody("""{"transferId":"$paymentId","replayed":true}"""),
            ),
        )

        val retry = pay(key = "key-recovered", amount = 3_000)

        assertThat(retry.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(retry.body!!["id"].asText())
            .`as`("the retry resolves the same payment, it does not start a new one")
            .isEqualTo(paymentId)
        assertThat(retry.body!!["status"].asText()).isEqualTo("COMPLETED")

        accountService.verify(
            postRequestedFor(urlEqualTo("/internal/transfers"))
                .withRequestBody(matchingJsonPath("$.transferId", equalToValue(paymentId))),
        )
    }

    @Test
    fun `a 5xx from account-service also leaves the payment pending`() {
        accountService.stubFor(
            post(urlEqualTo("/internal/transfers")).willReturn(aResponse().withStatus(503)),
        )

        val response = pay(key = "key-unavailable", amount = 1_500)

        assertThat(response.statusCode).isEqualTo(HttpStatus.ACCEPTED)
        assertThat(response.body!!["status"].asText())
            .`as`("a 503 says nothing about whether the transfer ran")
            .isEqualTo("PENDING")
    }

    @Test
    fun `a missing Idempotency-Key header is rejected`() {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val response = rest.exchange(
            "/payments",
            HttpMethod.POST,
            HttpEntity(body(amount = 100), headers),
            JsonNode::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(accountServiceRequests()).isZero()
    }

    @Test
    fun `an invalid body is answered with field-level problem details`() {
        val response = payRaw(
            key = "key-invalid",
            body = mapOf(
                "fromAccountId" to 7,
                "toAccountId" to 7,
                "amount" to 0,
                "currency" to "eur",
            ),
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.body!!["errors"].map { it["field"].asText() })
            .contains("amount", "currency", "distinctAccounts")
        assertThat(payments.findByIdempotencyKey("key-invalid"))
            .`as`("an invalid request must not leave a payment behind")
            .isNull()
    }

    @Test
    fun `an unknown payment is a 404 problem detail`() {
        val response = rest.getForEntity("/payments/${UUID.randomUUID()}", JsonNode::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(response.body!!["type"].asText()).endsWith("/problems/payment-not-found")
    }

    @Test
    fun `the database refuses to move a payment out of a terminal state`() {
        accountServiceAccepts()
        val id = pay(key = "key-terminal", amount = 2_000).body!!["id"].asText()

        assertThatThrownBy { jdbc.update("update payments set status = 'FAILED', failure_reason = 'x' where id = ?::uuid", id) }
            .`as`("COMPLETED and FAILED are final, and the database enforces it")
            .hasMessageContaining("cannot become")
    }

    @Test
    fun `a completed payment is stored as completed`() {
        accountServiceAccepts()
        val id = UUID.fromString(pay(key = "key-stored", amount = 1_234).body!!["id"].asText())

        val stored = payments.findById(id).orElseThrow()
        assertThat(stored.status).isEqualTo(PaymentStatus.COMPLETED)
        assertThat(stored.idempotencyKey).isEqualTo("key-stored")
        assertThat(stored.amount).isEqualTo(1_234)
        assertThat(stored.failureReason).isNull()
    }

    private fun accountServiceAccepts() {
        accountService.stubFor(
            post(urlEqualTo("/internal/transfers")).willReturn(
                aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                    .withBody("""{"replayed":false}"""),
            ),
        )
    }

    private fun body(amount: Long) = mapOf(
        "fromAccountId" to 1,
        "toAccountId" to 2,
        "amount" to amount,
        "currency" to "EUR",
    )

    private fun pay(key: String, amount: Long): ResponseEntity<JsonNode> = payRaw(key, body(amount))

    private fun payRaw(key: String, body: Map<String, Any>): ResponseEntity<JsonNode> {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set("Idempotency-Key", key)
        }
        return rest.exchange("/payments", HttpMethod.POST, HttpEntity(body, headers), JsonNode::class.java)
    }

    private fun equalToValue(expected: String) =
        com.github.tomakehurst.wiremock.client.WireMock.equalTo(expected)
}
