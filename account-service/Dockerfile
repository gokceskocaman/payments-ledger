# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------------------------------
# Build: a full JDK and the whole multi-module source tree.
# ---------------------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Build scripts before sources. These change rarely, so the Gradle distribution download and the
# dependency resolution below stay cached when only Kotlin files change -- which is almost always.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY account-service/build.gradle.kts account-service/
COPY payment-service/build.gradle.kts payment-service/
RUN ./gradlew --no-daemon :account-service:dependencies --configuration runtimeClasspath > /dev/null

COPY account-service/src account-service/src
# Tests are not run here: they need Docker, and a build that needs Docker inside Docker is a bad
# trade. CI runs the full suite before anything is built into an image.
RUN ./gradlew --no-daemon :account-service:bootJar

# Split the fat jar into layers that change at different rates, so a new build of the application
# reuses the (much larger) dependency layer instead of shipping it again.
RUN java -Djarmode=tools -jar account-service/build/libs/account-service-*.jar \
    extract --layers --launcher --destination extracted

# ---------------------------------------------------------------------------------------------------
# Runtime: a JRE only. No compiler, no build tool, no source.
# ---------------------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# Never root: a container escape should not start with privileges it never needed.
RUN addgroup -S app && adduser -S -G app app
USER app

# Ordered least- to most-frequently-changed, which is the order Docker caches them in.
COPY --from=build --chown=app:app /workspace/extracted/dependencies/ ./
COPY --from=build --chown=app:app /workspace/extracted/spring-boot-loader/ ./
COPY --from=build --chown=app:app /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /workspace/extracted/application/ ./

EXPOSE 8081

# MaxRAMPercentage rather than -Xmx: the JVM then sizes its heap from the container's memory limit
# instead of from the host's. Extra flags go in JAVA_TOOL_OPTIONS, which the JVM reads on its own.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "org.springframework.boot.loader.launch.JarLauncher"]
