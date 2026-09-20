import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// The root project builds nothing itself. It owns plugin versions and the settings
// that must be identical in every service, so the modules stay thin.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.kotlin.jpa) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    // all-open: Spring needs to subclass @Component/@Configuration classes, Kotlin classes are final by default.
    apply(plugin = "org.jetbrains.kotlin.plugin.spring")
    // no-arg: JPA needs a no-argument constructor on @Entity classes, Kotlin data classes have none.
    apply(plugin = "org.jetbrains.kotlin.plugin.jpa")
    apply(plugin = "org.springframework.boot")
    // Imports the Spring Boot BOM, so dependency coordinates below are declared without versions.
    apply(plugin = "io.spring.dependency-management")

    group = "dev.gokce.payments"
    version = "0.0.1-SNAPSHOT"

    // Toolchain, not "whatever JDK is on PATH": Gradle downloads/selects a JDK 21
    // so local builds and CI compile against the same thing.
    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            // Treat JSR-305 nullability annotations on Java APIs (Spring, Jakarta) as strict Kotlin types.
            freeCompilerArgs.addAll("-Xjsr305=strict")
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // Testcontainers ships docker-java, which still negotiates Docker API 1.32. Docker Engine 29
        // rejects anything below 1.40 ("client version 1.32 is too old"), so pin the version the
        // client asks for. 1.40 has been supported since Docker 19.03, so this works on old and new
        // daemons alike -- including whatever version CI happens to run.
        systemProperty("api.version", "1.40")
        // Testcontainers' cleanup container (Ryuk) bind-mounts the Docker socket. It mounts the
        // *host* path it connected through, which on Docker Desktop is a socket inside
        // ~/Library/Containers that the VM cannot mount. Inside a container the socket is always at
        // /var/run/docker.sock -- on Docker Desktop and on a Linux CI runner alike.
        environment("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE", "/var/run/docker.sock")
    }

    dependencies {
        // Typed accessors (implementation(...)) only exist inside a module's own build file,
        // hence the string notation here. These four are needed by every service.
        "implementation"("org.jetbrains.kotlin:kotlin-reflect")
        "implementation"("com.fasterxml.jackson.module:jackson-module-kotlin")
        "testImplementation"("org.springframework.boot:spring-boot-starter-test")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
}
