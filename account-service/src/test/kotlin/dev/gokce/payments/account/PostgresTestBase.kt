package dev.gokce.payments.account

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.testcontainers.containers.PostgreSQLContainer
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
 * Tests are deliberately not `@Transactional`: requests go over real HTTP, so the server does its
 * work on its own connection and a test-managed rollback would not touch it. Each test therefore
 * creates the accounts it needs, and assertions about shared state (the funding account, totals)
 * compare deltas rather than absolute values.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class PostgresTestBase {
    companion object {
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer<Nothing> =
            PostgreSQLContainer<Nothing>(DockerImageName.parse("postgres:16-alpine"))
                .apply { start() }
    }
}
