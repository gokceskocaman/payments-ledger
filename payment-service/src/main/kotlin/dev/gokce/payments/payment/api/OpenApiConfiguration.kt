package dev.gokce.payments.payment.api

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfiguration {

    @Bean
    fun openApi(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("payment-service API")
                .version("0.0.1")
                .description("Payment requests. `POST /payments` is idempotent on the `Idempotency-Key` header: the same key with the same body returns the stored payment, the same key with a different body is rejected. A payment whose outcome could not be determined stays `PENDING` rather than being guessed."),
        )
        .components(
            Components().addSecuritySchemes(
                BEARER_SCHEME,
                SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")
                    .description(
                        "A JWT bearer token. Locally: `scripts/dev-token.sh` for a customer token, " +
                            "or `scripts/dev-token.sh ledger:internal` for a service token. " +
                            "The dev tokens are signed with a symmetric secret checked into this " +
                            "repository and are worthless anywhere else.",
                    ),
            ),
        )
        // Applied to every operation; the public paths are public in the security filter chain, not here.
        .addSecurityItem(SecurityRequirement().addList(BEARER_SCHEME))

    private companion object {
        const val BEARER_SCHEME = "bearerAuth"
    }
}
