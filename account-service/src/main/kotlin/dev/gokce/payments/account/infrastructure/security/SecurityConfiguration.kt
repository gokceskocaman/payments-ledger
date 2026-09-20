package dev.gokce.payments.account.infrastructure.security

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.web.SecurityFilterChain
import java.net.URI
import javax.crypto.spec.SecretKeySpec

/**
 * This service is an OAuth2 **resource server**: it never issues tokens, it only validates the ones
 * it is given. Validation is local -- signature, expiry and issuer -- so no call leaves the process to
 * check a request, which is the point of JWTs over opaque tokens.
 *
 * Duplicated between the two services because the build has no shared module. Extracting a `common`
 * module would be the right move the moment a third service appears; with two, a shared module costs
 * more in coupling than it saves in lines.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(JwtProperties::class)
class SecurityConfiguration(private val objectMapper: ObjectMapper) {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain = http
        .authorizeHttpRequests { requests ->
            requests
                // Public, and only these: liveness for orchestrators, and the API description.
                .requestMatchers(*PUBLIC_PATHS).permitAll()
                // Service-to-service only. A customer's token must never reach the ledger's internals,
                // however valid it is.
                .requestMatchers("/internal/**").hasAuthority(SERVICE_AUTHORITY)
                // Default deny: anything not named above needs a valid token. New endpoints are
                // protected by omission rather than by remembering to add them here.
                .anyRequest().authenticated()
        }
        .oauth2ResourceServer { resourceServer ->
            resourceServer.jwt { }
            // 401 and 403 as RFC 7807, like every other error this service returns.
            resourceServer.authenticationEntryPoint { _, response, _ ->
                response.setHeader("WWW-Authenticate", "Bearer")
                writeProblem(
                    response,
                    HttpStatus.UNAUTHORIZED,
                    "unauthenticated",
                    "Unauthenticated",
                    "A valid bearer token is required",
                )
            }
            resourceServer.accessDeniedHandler { _, response, _ ->
                writeProblem(
                    response,
                    HttpStatus.FORBIDDEN,
                    "insufficient-scope",
                    "Insufficient scope",
                    "The token is valid but lacks the scope required for this endpoint",
                )
            }
        }
        // Nothing is stored between requests: every call carries its own credential.
        .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        // CSRF defends against a browser attaching an *ambient* credential (a cookie) to a
        // cross-site request. There are no cookies and no sessions here, so there is nothing
        // ambient to ride on, and a bearer token has to be added deliberately by the caller.
        .csrf { it.disable() }
        .build()

    /**
     * DEV ONLY: a symmetric secret. `JwtValidators.createDefaultWithIssuer` checks expiry, not-before
     * and issuer; audience validation would be the next thing to add once there is more than one
     * audience to distinguish.
     */
    @Bean
    fun jwtDecoder(properties: JwtProperties): JwtDecoder {
        val key = SecretKeySpec(properties.hmacSecret.toByteArray(Charsets.UTF_8), "HmacSHA256")
        return NimbusJwtDecoder.withSecretKey(key)
            .macAlgorithm(MacAlgorithm.HS256)
            .build()
            .apply { setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer)) }
    }

    private fun writeProblem(
        response: HttpServletResponse,
        status: HttpStatus,
        type: String,
        title: String,
        detail: String,
    ) {
        val problem = ProblemDetail.forStatus(status).apply {
            this.type = URI.create("https://payments-ledger.gokce.dev/problems/$type")
            this.title = title
            this.detail = detail
        }
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        objectMapper.writeValue(response.outputStream, problem)
    }

    companion object {
        /** A JWT `scope` of "ledger:internal" becomes this authority. */
        const val SERVICE_AUTHORITY = "SCOPE_ledger:internal"

        private val PUBLIC_PATHS = arrayOf(
            "/actuator/health",
            "/actuator/health/**",
            "/actuator/info",
            "/v3/api-docs",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
        )
    }
}
