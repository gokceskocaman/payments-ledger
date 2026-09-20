package dev.gokce.payments.account

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.kafka.ConfluentKafkaContainer
import org.testcontainers.utility.DockerImageName

/**
 * One Postgres container and one Spring context for every integration test in this module.
 *
 * The container is a started singleton rather than a JUnit-managed `@Container`: the
 * `@Testcontainers` extension stops a static container once the class that declares it has finished,
 * which would leave every later test class talking to a closed port. Started once here, it lives for
 * the whole JVM, and Testcontainers' reaper removes it when the JVM exits. Because every subclass
 * shares this identical `@SpringBootTest` configuration, Spring's test context cache also hands them
 * all the same application context, so the service starts once too.
 *
 * `@ServiceConnection` points `spring.datasource.*` at the container, so no property wiring is needed.
 *
 * The broker is here too, because the payment-event consumer is part of the running application:
 * without it the context would fail to start.
 *
 * Tests are deliberately not `@Transactional`: requests go over real HTTP, so the server does its
 * work on its own connection and a test-managed rollback would not touch it. Each test therefore
 * creates the accounts it needs, and assertions about shared state (the funding account, totals)
 * compare deltas rather than absolute values.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class PostgresTestBase {
    companion object {
        /**
         * Kafka in KRaft mode, one broker, no ZooKeeper -- as in docker-compose.yml, but pinned to
         * Kafka 3.8 rather than the 3.9 Compose runs. Testcontainers cannot start a 3.9 broker: it
         * formats the storage directory with placeholder listeners and only rewrites them once the
         * container is up, and 3.9's `kafka-storage format` began validating that config, failing with
         * "advertised.listeners cannot use the nonroutable meta-address 0.0.0.0".
         */
        @ServiceConnection
        @JvmStatic
        val kafka: ConfluentKafkaContainer =
            ConfluentKafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.8.0"))
                .apply { start() }

        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer<Nothing> =
            PostgreSQLContainer<Nothing>(DockerImageName.parse("postgres:16-alpine"))
                .apply { start() }
    }

    @org.springframework.beans.factory.annotation.Autowired
    protected lateinit var jwtProperties: dev.gokce.payments.account.infrastructure.security.JwtProperties

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
