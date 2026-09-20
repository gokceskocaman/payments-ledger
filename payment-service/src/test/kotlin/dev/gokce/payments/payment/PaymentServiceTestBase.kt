package dev.gokce.payments.payment

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import org.junit.jupiter.api.BeforeEach
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.kafka.ConfluentKafkaContainer
import org.testcontainers.utility.DockerImageName

/**
 * A real Postgres and a real HTTP server standing in for account-service.
 *
 * WireMock rather than a mocked [dev.gokce.payments.payment.infrastructure.accounts.AccountServiceClient]
 * because the case that matters most -- a read timeout -- does not exist above the socket. A mock can
 * be told to throw; only a real server can be told to go quiet for two seconds and let the client's
 * own timeout decide what happens.
 *
 * The read timeout is squeezed to 300ms here so the timeout test costs a fraction of a second rather
 * than the two seconds production waits.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class PaymentServiceTestBase {

    companion object {
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer<Nothing> =
            PostgreSQLContainer<Nothing>(DockerImageName.parse("postgres:16-alpine"))
                .apply { start() }

        /**
         * Kafka in KRaft mode, one broker, no ZooKeeper -- as in docker-compose.yml.
         *
         * Pinned to Kafka 3.8 (cp-kafka 7.8) rather than the 3.9 that Compose runs, because
         * Testcontainers cannot start a 3.9 broker: it formats the storage directory with placeholder
         * listeners and only rewrites them once the container is up, and Kafka 3.9's `kafka-storage
         * format` began validating that config, failing with "advertised.listeners cannot use the
         * nonroutable meta-address 0.0.0.0". Nothing this service uses differs between the two.
         *
         * It lives in the shared base so every payment test runs in one Spring context against one
         * broker, rather than paying for a second container and a second context.
         */
        @ServiceConnection
        @JvmStatic
        val kafka: ConfluentKafkaContainer =
            ConfluentKafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.8.0"))
                .apply { start() }

        @JvmStatic
        val accountService: WireMockServer = WireMockServer(
            WireMockConfiguration.options()
                .dynamicPort()
                // The real account-service is Tomcat, which serves plaintext HTTP/1.1. Left enabled,
                // WireMock advertises h2c, the JDK HttpClient behind RestClient attempts the upgrade
                // and the exchange dies with RST_STREAM -- a protocol quirk of the stub that
                // production would never show.
                .http2PlainDisabled(true),
        ).apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun wireAccountService(registry: DynamicPropertyRegistry) {
            registry.add("account-service.base-url") { "http://localhost:${accountService.port()}" }
            registry.add("account-service.read-timeout") { "300ms" }
            registry.add("account-service.connect-timeout") { "300ms" }
            // Relay fast, so tests wait tens of milliseconds for an event rather than a second.
            registry.add("outbox.poll-interval") { "100ms" }
            registry.add("outbox.topic-replicas") { "1" }
        }
    }

    @BeforeEach
    fun resetAccountService() {
        accountService.resetAll()
    }

    protected fun accountServiceRequests(): Int =
        accountService.findAll(WireMock.postRequestedFor(WireMock.urlEqualTo("/internal/transfers"))).size

    @org.springframework.beans.factory.annotation.Autowired
    protected lateinit var jwtProperties: dev.gokce.payments.payment.infrastructure.security.JwtProperties

    @org.springframework.beans.factory.annotation.Autowired
    protected lateinit var http: org.springframework.boot.test.web.client.TestRestTemplate

    /**
     * Every request in these tests carries a bearer token, because every endpoint except health and
     * the API docs requires one. The token is minted with the service's own configured secret, so the
     * real decoder validates it.
     */
    protected fun jsonHeaders(
        scope: String = DevTokens.CUSTOMER_SCOPE,
        extra: Map<String, String> = emptyMap(),
    ): org.springframework.http.HttpHeaders =
        org.springframework.http.HttpHeaders().apply {
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            setBearerAuth(DevTokens.signed(jwtProperties, scope))
            extra.forEach { (name, value) -> set(name, value) }
        }

    protected fun <T> getJson(
        url: String,
        type: Class<T>,
        scope: String = DevTokens.CUSTOMER_SCOPE,
    ): org.springframework.http.ResponseEntity<T> = http.exchange(
        url,
        org.springframework.http.HttpMethod.GET,
        org.springframework.http.HttpEntity<Void>(jsonHeaders(scope)),
        type,
    )

    protected fun <T> postJson(
        url: String,
        body: Any,
        type: Class<T>,
        scope: String = DevTokens.CUSTOMER_SCOPE,
        extraHeaders: Map<String, String> = emptyMap(),
    ): org.springframework.http.ResponseEntity<T> = http.exchange(
        url,
        org.springframework.http.HttpMethod.POST,
        org.springframework.http.HttpEntity(body, jsonHeaders(scope, extraHeaders)),
        type,
    )
}
