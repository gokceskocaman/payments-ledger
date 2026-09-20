package dev.gokce.payments.payment.infrastructure.accounts

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient

@Configuration
@EnableConfigurationProperties(AccountServiceProperties::class)
class AccountServiceClientConfiguration {

    /**
     * Timeouts are set explicitly because the defaults are "wait forever". An HTTP client without a
     * read timeout will hold a request thread for as long as the far side is willing to stay silent,
     * so one stuck dependency takes the whole service down with it.
     */
    @Bean
    fun accountServiceRestClient(
        builder: RestClient.Builder,
        properties: AccountServiceProperties,
    ): RestClient = builder
        .baseUrl(properties.baseUrl)
        .requestFactory(
            ClientHttpRequestFactoryBuilder.detect().build(
                ClientHttpRequestFactorySettings.defaults()
                    .withConnectTimeout(properties.connectTimeout)
                    .withReadTimeout(properties.readTimeout),
            ),
        )
        .build()
}
