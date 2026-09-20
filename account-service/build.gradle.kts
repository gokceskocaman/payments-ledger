// Plugins, toolchain and shared dependencies come from the root build file.
// Only this service's own dependencies live here.
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    // Bean Validation: the web starter brings the annotations' API but no implementation,
    // so @Valid on a request body is silently ignored without Hibernate Validator.
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Validates the JWT on every request. The resource server never issues tokens; it only checks
    // them, which is why it needs no client credentials of its own.
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

    implementation(libs.springdoc.openapi)

    // Flyway 10+ ships database support in separate modules; without the postgresql one
    // startup fails with "Unsupported Database: PostgreSQL".
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // Consumer only: this service subscribes to payment events, it publishes none.
    implementation("org.springframework.kafka:spring-kafka")

    runtimeOnly("org.postgresql:postgresql")

    // Integration tests run against a real Postgres, never H2: the schema relies on partial
    // unique indexes, CHECK constraints and a plpgsql trigger that H2 does not have.
    // spring-boot-testcontainers supplies @ServiceConnection, which points the datasource at
    // the container without any manual property wiring.
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:kafka")
}
