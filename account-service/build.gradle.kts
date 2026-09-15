// Plugins, toolchain and shared dependencies come from the root build file.
// Only this service's own dependencies live here.
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    // Flyway 10+ ships database support in separate modules; without the postgresql one
    // startup fails with "Unsupported Database: PostgreSQL".
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    runtimeOnly("org.postgresql:postgresql")
}
