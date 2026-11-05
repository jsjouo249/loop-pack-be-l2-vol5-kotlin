package com.loopers.week7

import com.loopers.application.purchase.PurchaseActivityService
import com.loopers.application.purchase.PurchaseActivityTransaction
import com.loopers.config.PurchaseKafkaConfiguration
import com.loopers.config.jpa.DataSourceConfig
import com.loopers.infrastructure.purchase.JpaPurchaseActivityRepository
import com.loopers.infrastructure.purchase.PurchaseActivityJpaEntity
import com.loopers.interfaces.consumer.PurchaseActivityConsumer
import com.loopers.interfaces.consumer.DemoKafkaConsumer
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.test.context.TestComponent
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.FilterType
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.transaction.annotation.EnableTransactionManagement
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

@SpringBootApplication
@ComponentScan(
    basePackages = ["com.loopers.application", "com.loopers.infrastructure", "com.loopers.interfaces", "com.loopers.config"],
    excludeFilters = [
        ComponentScan.Filter(type = FilterType.ANNOTATION, classes = [TestComponent::class]),
        ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = [DemoKafkaConsumer::class]),
    ],
)
@ConfigurationPropertiesScan("com.loopers.application.outbox")
class OutboxE2eApplication {
    @Bean
    @Primary
    fun controlledClock() = MutableOutboxClock()
}

@Configuration
@EnableAutoConfiguration
@EnableTransactionManagement
@EntityScan(basePackageClasses = [PurchaseActivityJpaEntity::class])
@Import(
    DataSourceConfig::class,
    JpaPurchaseActivityRepository::class,
    PurchaseActivityService::class,
    PurchaseActivityTransaction::class,
    PurchaseActivityConsumer::class,
    PurchaseKafkaConfiguration::class,
)
class PurchaseOnlyConfiguration

class MutableOutboxClock : Clock() {
    private var current = Instant.parse("2026-11-05T00:00:00Z")

    override fun instant(): Instant = current
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this

    fun advance() {
        current = current.plusSeconds(301)
    }
}
