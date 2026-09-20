package dev.gokce.payments.payment

import com.fasterxml.jackson.databind.JsonNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import java.util.UUID

/**
 * Who may call what. Health and the API description are open; everything else needs a valid token.
 *
 * payment-service exposes no `/internal` endpoints today, but the rule denying them to anything
 * without the service scope is part of the shared security posture -- so the first internal endpoint
 * anyone adds here is protected by default rather than by remembering to protect it.
 */
class SecurityIntegrationTest : PaymentServiceTestBase() {

    private val paymentBody = mapOf(
        "fromAccountId" to 1,
        "toAccountId" to 2,
        "amount" to 500,
        "currency" to "EUR",
    )

    @Test
    fun `health and api docs are public`() {
        assertThat(anonymousGet("/actuator/health").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(anonymousGet("/v3/api-docs").statusCode).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `the api docs describe the bearer scheme, the endpoints and the examples`() {
        val docs = anonymousGet("/v3/api-docs").body!!

        assertThat(docs["components"]["securitySchemes"]["bearerAuth"]["scheme"].asText()).isEqualTo("bearer")
        assertThat(docs["paths"].fieldNames().asSequence().toList()).contains("/payments", "/payments/{id}")

        val post = docs["paths"]["/payments"]["post"]
        assertThat(post["parameters"].map { it["name"].asText() })
            .`as`("the Idempotency-Key header is part of the documented contract")
            .contains("Idempotency-Key")

        // springdoc writes "examples" when an operation has several named ones and "example" when it
        // has one, so the assertions name the shape each response actually produces.
        val created = post["responses"]["201"]["content"]["*/*"]["examples"]
        assertThat(created.fieldNames().asSequence().toList())
            .`as`("both outcomes of a created payment are shown")
            .containsExactlyInAnyOrder("completed", "failed")

        assertThat(created["completed"]["value"]["status"].asText()).isEqualTo("COMPLETED")
        assertThat(created["failed"]["value"]["failureReason"].asText()).contains("cannot cover a debit")

        assertThat(post["responses"]["202"]["content"]["*/*"]["example"]["status"].asText())
            .`as`("the in-doubt response is documented with an example")
            .isEqualTo("PENDING")

        assertThat(post["responses"]["422"]["content"]["application/problem+json"]["example"]["type"].asText())
            .contains("idempotency-key-reused")

        assertThat(docs["components"]["schemas"]["CreatePaymentRequest"]["properties"]["amount"]["example"].asLong())
            .`as`("request fields carry examples in minor units, so nobody sends 120.00")
            .isEqualTo(12_000)
    }

    @Test
    fun `a request with no token is 401`() {
        val response = anonymousGet("/payments/${UUID.randomUUID()}")

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(response.headers["WWW-Authenticate"]).isNotNull()
        assertThat(response.body!!["type"].asText()).endsWith("/problems/unauthenticated")
    }

    @Test
    fun `an expired token is 401`() {
        val response = getWithToken("/payments/${UUID.randomUUID()}", DevTokens.expired(jwtProperties))

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `a token signed with the wrong key is 401`() {
        val response = getWithToken("/payments/${UUID.randomUUID()}", DevTokens.wronglySigned(jwtProperties))

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `a token from the wrong issuer is 401`() {
        val token = DevTokens.signed(jwtProperties, DevTokens.CUSTOMER_SCOPE, issuer = "https://evil.example")

        assertThat(getWithToken("/payments/${UUID.randomUUID()}", token).statusCode)
            .isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `posting a payment without a token never reaches the application`() {
        val response = http.exchange(
            "/payments",
            HttpMethod.POST,
            HttpEntity(paymentBody, HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }),
            JsonNode::class.java,
        )

        assertThat(response.statusCode)
            .`as`("rejected by the filter chain, before any Idempotency-Key or body validation")
            .isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `the internal path is reserved for the service scope`() {
        val response = postJson(
            "/internal/anything",
            emptyMap<String, Any>(),
            JsonNode::class.java,
            scope = DevTokens.CUSTOMER_SCOPE,
        )

        assertThat(response.statusCode)
            .`as`("authenticated but not authorised: 403, not 401 and not 404")
            .isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(response.body!!["type"].asText()).endsWith("/problems/insufficient-scope")
    }

    @Test
    fun `a valid customer token reaches the payments api`() {
        val response = postJson(
            "/payments",
            paymentBody,
            JsonNode::class.java,
            extraHeaders = mapOf("Idempotency-Key" to "security-${UUID.randomUUID()}"),
        )

        assertThat(response.statusCode)
            .`as`("security lets it through; what happens next is the domain's business")
            .isNotIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN)
    }

    private fun anonymousGet(path: String) =
        http.exchange(path, HttpMethod.GET, HttpEntity<Void>(HttpHeaders()), JsonNode::class.java)

    private fun getWithToken(path: String, token: String) = http.exchange(
        path,
        HttpMethod.GET,
        HttpEntity<Void>(HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON; setBearerAuth(token) }),
        JsonNode::class.java,
    )
}
