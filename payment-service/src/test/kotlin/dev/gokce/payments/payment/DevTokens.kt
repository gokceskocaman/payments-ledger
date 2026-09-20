package dev.gokce.payments.payment

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import dev.gokce.payments.payment.infrastructure.security.JwtProperties
import java.time.Duration
import java.time.Instant
import java.util.Date

/**
 * Mints tokens for tests using the service's own configured secret and issuer, so the tests exercise
 * the real [dev.gokce.payments.payment.infrastructure.security.SecurityConfiguration] wiring rather
 * than a decoder built specially for them.
 */
object DevTokens {

    const val CUSTOMER_SCOPE = "payments:write"
    const val SERVICE_SCOPE = "ledger:internal"

    fun signed(
        properties: JwtProperties,
        scope: String,
        subject: String = "test-subject",
        lifetime: Duration = Duration.ofMinutes(5),
        issuer: String = properties.issuer,
    ): String {
        val issuedAt = Instant.now()
        val claims = JWTClaimsSet.Builder()
            .issuer(issuer)
            .subject(subject)
            .claim("scope", scope)
            .issueTime(Date.from(issuedAt))
            .expirationTime(Date.from(issuedAt + lifetime))
            .build()
        return SignedJWT(JWSHeader(JWSAlgorithm.HS256), claims)
            .apply { sign(MACSigner(properties.hmacSecret.toByteArray(Charsets.UTF_8))) }
            .serialize()
    }

    /** Correctly signed but already expired -- must be rejected with 401, not accepted. */
    fun expired(properties: JwtProperties, scope: String = CUSTOMER_SCOPE): String {
        val issuedAt = Instant.now() - Duration.ofHours(2)
        val claims = JWTClaimsSet.Builder()
            .issuer(properties.issuer)
            .subject("test-subject")
            .claim("scope", scope)
            .issueTime(Date.from(issuedAt))
            .expirationTime(Date.from(issuedAt + Duration.ofMinutes(5)))
            .build()
        return SignedJWT(JWSHeader(JWSAlgorithm.HS256), claims)
            .apply { sign(MACSigner(properties.hmacSecret.toByteArray(Charsets.UTF_8))) }
            .serialize()
    }

    /** Signed with the wrong key: the signature must not verify. */
    fun wronglySigned(properties: JwtProperties): String {
        val claims = JWTClaimsSet.Builder()
            .issuer(properties.issuer)
            .subject("test-subject")
            .claim("scope", CUSTOMER_SCOPE)
            .expirationTime(Date.from(Instant.now() + Duration.ofMinutes(5)))
            .build()
        return SignedJWT(JWSHeader(JWSAlgorithm.HS256), claims)
            .apply { sign(MACSigner("a-completely-different-secret-of-sufficient-length".toByteArray())) }
            .serialize()
    }
}
