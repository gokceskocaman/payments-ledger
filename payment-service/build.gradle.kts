// Plugins, toolchain and shared dependencies come from the root build file.
// Only this service's own dependencies live here.
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    // Bean Validation: the web starter brings the annotations' API but no implementation,
    // so @Valid on a request body is silently ignored without Hibernate Validator.
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Flyway 10+ ships database support in separate modules; without the postgresql one
    // startup fails with "Unsupported Database: PostgreSQL".
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // Producer only for now: the relay publishes, nothing in this service consumes yet.
    implementation("org.springframework.kafka:spring-kafka")

    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:kafka")

    // A real HTTP server standing in for account-service. Needed rather than a mocked client
    // because the interesting case -- a read timeout -- only exists at the socket level.
    testImplementation(libs.wiremock)
}
