package dev.gokce.payments.payment.infrastructure.accounts

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import dev.gokce.payments.payment.infrastructure.security.JwtProperties
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.Date
import java.util.concurrent.atomic.AtomicReference

/**
 * Supplies the bearer token this service presents to the `/internal` endpoints of account-service.
 *
 * DEV ONLY in this form. It self-signs with the shared symmetric secret, which works precisely
 * *because* the dev setup is symmetric -- and that is the property production must not have. In
 * production this class is replaced by an OAuth2 client-credentials token: payment-service
 * authenticates to the identity provider with its own client id and secret, receives a token signed
 * by a key it does not possess, and caches it the same way this does. The interface stays the same;
 * only where the token comes from changes.
 */
@Component
class ServiceTokenProvider(private val jwtProperties: JwtProperties) {

    private val cached = AtomicReference<CachedToken?>(null)

    fun token(): String {
        val current = cached.get()
        if (current != null && current.usableAt(Instant.now())) return current.value

        val issuedAt = Instant.now()
        val expiresAt = issuedAt + TOKEN_LIFETIME
        val claims = JWTClaimsSet.Builder()
            .issuer(jwtProperties.issuer)
            .subject(SUBJECT)
            // account-service maps this to the SCOPE_ledger:internal authority.
            .claim("scope", SERVICE_SCOPE)
            .issueTime(Date.from(issuedAt))
            .expirationTime(Date.from(expiresAt))
            .build()

        val jwt = SignedJWT(JWSHeader(JWSAlgorithm.HS256), claims).apply {
            sign(MACSigner(jwtProperties.hmacSecret.toByteArray(Charsets.UTF_8)))
        }

        val minted = CachedToken(jwt.serialize(), expiresAt)
        cached.set(minted)
        return minted.value
    }

    private data class CachedToken(val value: String, val expiresAt: Instant) {
        /** Renewed early, so a token cannot expire in flight between here and the far side. */
        fun usableAt(now: Instant): Boolean = now.isBefore(expiresAt - RENEW_BEFORE_EXPIRY)
    }

    private companion object {
        const val SUBJECT = "payment-service"
        const val SERVICE_SCOPE = "ledger:internal"
        val TOKEN_LIFETIME: Duration = Duration.ofMinutes(5)
        val RENEW_BEFORE_EXPIRY: Duration = Duration.ofMinutes(1)
    }
}
