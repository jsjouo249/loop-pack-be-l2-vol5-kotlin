package com.loopers.infrastructure.payment

import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import org.apache.hc.client5.http.config.ConnectionConfig
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder
import org.apache.hc.core5.util.Timeout
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.net.URI
import java.time.Clock
import java.time.Duration

@ConfigurationProperties("payment.pg")
data class PgProperties(
    val baseUrl: String = "http://127.0.0.1:18080",
    val callbackUrl: String = "http://127.0.0.1:8080/api/v1/payments/callback",
) {
    init {
        listOf(baseUrl, callbackUrl).forEach {
            val uri = URI.create(it)
            require(
                uri.scheme == "http" && uri.host == "127.0.0.1" && uri.userInfo == null &&
                    uri.fragment == null && uri.query == null,
            ) {
                "PG URLs must use local HTTP on 127.0.0.1"
            }
        }
    }
}

@Configuration
@EnableConfigurationProperties(PgProperties::class)
class PgConfiguration {
    @Bean
    fun pgCircuitBreaker(): CircuitBreaker = circuit(Clock.systemUTC())

    @Bean
    fun pgRequestFactory(): HttpComponentsClientHttpRequestFactory {
        val manager = PoolingHttpClientConnectionManagerBuilder.create().setDefaultConnectionConfig(
            ConnectionConfig.custom().setConnectTimeout(
                Timeout.ofMilliseconds(200),
            ).setSocketTimeout(Timeout.ofMilliseconds(500)).build(),
        ).build()
        val http = HttpClients.custom().setConnectionManager(manager).disableAutomaticRetries().disableRedirectHandling().build()
        return HttpComponentsClientHttpRequestFactory(http).also {
            it.setConnectionRequestTimeout(Duration.ofMillis(100))
            it.setReadTimeout(Duration.ofMillis(500))
        }
    }

    @Bean
    fun pgRestClient(
        properties: PgProperties,
        pgRequestFactory: HttpComponentsClientHttpRequestFactory,
    ): RestClient = RestClient.builder()
        .baseUrl(properties.baseUrl).defaultHeader("X-USER-ID", "135135").requestFactory(pgRequestFactory).build()

    companion object {
        fun circuit(clock: Clock): CircuitBreaker = CircuitBreaker.of(
            "pgLookup",
            CircuitBreakerConfig.custom()
            .clock(clock).slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(4).minimumNumberOfCalls(4).failureRateThreshold(50f)
            .slowCallDurationThreshold(Duration.ofMillis(400)).slowCallRateThreshold(50f)
            .waitDurationInOpenState(Duration.ofSeconds(5)).permittedNumberOfCallsInHalfOpenState(1).build(),
        )
    }
}
