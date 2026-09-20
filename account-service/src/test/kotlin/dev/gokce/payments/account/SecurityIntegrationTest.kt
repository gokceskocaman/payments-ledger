package dev.gokce.payments.account

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
 * Who may call what. Health and the API description are open; everything else needs a valid token,
 * and `/internal` needs the service scope on top of that.
 */
class SecurityIntegrationTest : PostgresTestBase() {

    private val transferBody = mapOf(
        "transferId" to UUID.randomUUID(),
        "fromAccountId" to 1,
        "toAccountId" to 2,
        "amount" to 100,
        "currency" to "EUR",
    )

    @Test
    fun `health and api docs are public`() {
        assertThat(anonymousGet("/actuator/health").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(anonymousGet("/v3/api-docs").statusCode)
            .`as`("the API description has to be readable to be useful")
            .isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `the api docs describe the bearer scheme and the endpoints`() {
        val docs = anonymousGet("/v3/api-docs").body!!
        assertThat(docs["components"]["securitySchemes"]["bearerAuth"]["scheme"].asText()).isEqualTo("bearer")
        assertThat(docs["paths"].fieldNames().asSequence().toList())
            .contains("/accounts", "/accounts/{id}", "/internal/transfers")

        val transfer = docs["paths"]["/internal/transfers"]["post"]
        val transferExamples = transfer["responses"]["200"]["content"]["*/*"]["examples"]
        assertThat(transferExamples.fieldNames().asSequence().toList())
            .`as`("a first post and a replay look different enough to be worth showing both")
            .containsExactlyInAnyOrder("posted", "replayed")
        assertThat(transferExamples["posted"]["value"]["replayed"].asBoolean()).isFalse()
        assertThat(transferExamples["replayed"]["value"]["replayed"].asBoolean()).isTrue()
        assertThat(docs["components"]["schemas"]["DepositRequest"]["properties"]["amount"]["example"].asLong())
            .`as`("amounts are documented in minor units")
            .isEqualTo(25_000)
    }

    @Test
    fun `a request with no token is 401`() {
        val response = anonymousGet("/accounts/1")

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(response.headers["WWW-Authenticate"])
            .`as`("RFC 6750 says say so")
            .isNotNull()
        assertThat(response.body!!["type"].asText()).endsWith("/problems/unauthenticated")
    }

    @Test
    fun `an expired token is 401`() {
        val response = getWithToken("/accounts/1", DevTokens.expired(jwtProperties))

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `a token signed with the wrong key is 401`() {
        val response = getWithToken("/accounts/1", DevTokens.wronglySigned(jwtProperties))

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `a token from the wrong issuer is 401`() {
        val token = DevTokens.signed(jwtProperties, DevTokens.CUSTOMER_SCOPE, issuer = "https://evil.example")

        assertThat(getWithToken("/accounts/1", token).statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `a customer token cannot reach the internal transfer endpoint`() {
        val response = postJson(
            "/internal/transfers",
            transferBody,
            JsonNode::class.java,
            scope = DevTokens.CUSTOMER_SCOPE,
        )

        assertThat(response.statusCode)
            .`as`("authenticated but not authorised: 403, not 401")
            .isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(response.body!!["type"].asText()).endsWith("/problems/insufficient-scope")
    }

    @Test
    fun `the internal transfer endpoint is reachable with the service scope`() {
        val response = postJson(
            "/internal/transfers",
            transferBody,
            JsonNode::class.java,
            scope = DevTokens.SERVICE_SCOPE,
        )

        // 404 because accounts 1 and 2 are the seeded system account and whatever else exists -- the
        // point is that authorisation let the request through to the business rules.
        assertThat(response.statusCode)
            .`as`("the service scope gets past security; what it then hits is the domain's business")
            .isNotIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN)
    }

    @Test
    fun `a valid customer token reaches the accounts api`() {
        val response = postJson(
            "/accounts",
            mapOf("ownerName" to "Security Test", "currency" to "EUR"),
            JsonNode::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
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
