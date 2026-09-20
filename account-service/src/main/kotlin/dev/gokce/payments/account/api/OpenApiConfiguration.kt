package dev.gokce.payments.account.api

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
                .title("account-service API")
                .version("0.0.1")
                .description("Accounts, balances and the double-entry ledger. Every movement of money writes one DEBIT and one CREDIT entry in a single transaction, and `accounts.balance` is a cache of those entries rather than an independent value."),
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
