package dev.gokce.payments.payment.infrastructure.security

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "security.jwt")
data class JwtProperties(
    /**
     * DEV ONLY. A symmetric HS256 secret, which means anyone holding it can *mint* tokens as well as
     * verify them -- fine for a laptop, unacceptable anywhere else. Production replaces this with
     * `spring.security.oauth2.resourceserver.jwt.jwk-set-uri`, after which this service holds no
     * signing material at all and can only verify. See docs/design-notes.md.
     */
    val hmacSecret: String,
    /** Rejected if the token's `iss` claim does not match. */
    val issuer: String,
)
