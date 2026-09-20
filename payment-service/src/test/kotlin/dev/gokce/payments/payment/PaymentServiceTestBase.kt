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
        }
    }

    @BeforeEach
    fun resetAccountService() {
        accountService.resetAll()
    }

    protected fun accountServiceRequests(): Int =
        accountService.findAll(WireMock.postRequestedFor(WireMock.urlEqualTo("/internal/transfers"))).size
}
