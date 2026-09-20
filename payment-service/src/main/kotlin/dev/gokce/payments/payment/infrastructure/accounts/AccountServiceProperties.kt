package dev.gokce.payments.payment.infrastructure.accounts

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "account-service")
data class AccountServiceProperties(
    val baseUrl: String,
    /** Failing to get a socket is a fast, unambiguous failure -- no reason to wait long for it. */
    val connectTimeout: Duration = Duration.ofMillis(500),
    /**
     * The dangerous one. Too short and healthy transfers are abandoned mid-flight, each leaving a
     * payment whose outcome has to be chased; too long and a stalled account-service holds this
     * service's threads until it falls over too. 2s comfortably covers a transfer (two row locks and
     * four small writes) while still shedding load quickly when the far side is unhealthy.
     */
    val readTimeout: Duration = Duration.ofSeconds(2),
)
