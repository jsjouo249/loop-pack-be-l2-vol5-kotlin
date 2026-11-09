package com.loopers.week7fixture

import com.loopers.CommerceApiApplication
import com.loopers.domain.order.event.OrderConfirmedPublisher
import com.loopers.infrastructure.order.KafkaOrderConfirmedPublisher
import com.loopers.application.user.UserInitializationService
import com.loopers.application.point.PointApplicationService
import com.loopers.application.brand.BrandApplicationService
import com.loopers.application.product.ProductApplicationService
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import java.util.concurrent.atomic.AtomicBoolean

// Included only in the archived W6 test executable, never in the W7 product.
@Configuration
class Week6Preparation {
    @Bean
    fun preparationSeed(
        users: UserInitializationService,
        points: PointApplicationService,
        brands: BrandApplicationService,
        products: ProductApplicationService,
    ) = ApplicationRunner { args ->
        val user = users.create("W7 synthetic buyer").id
        points.charge(user, 10_000)
        val brand = brands.create("W7 synthetic brand").id
        val first = products.create(brand, "W7 item A", 100, 10).id
        val second = products.create(brand, "W7 item B", 200, 10).id
        val manifest = java.nio.file.Path.of(args.getOptionValues("fixture-manifest").single())
        val pending = manifest.resolveSibling("${manifest.fileName}.pending")
        java.nio.file.Files.writeString(pending, "$user,$first,$second")
        java.nio.file.Files.move(pending, manifest, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    @Bean
    @Primary
    fun interruptedPublisher(actual: KafkaOrderConfirmedPublisher): OrderConfirmedPublisher {
        val first = AtomicBoolean(true)

        return OrderConfirmedPublisherFixture { event ->
            if (first.compareAndSet(true, false)) {
                error("WEEK7_W6_PREPARATION committed; stopped immediately before first send eventId=${event.eventId}")
            }
            actual.publish(event)
        }
    }
}

private class OrderConfirmedPublisherFixture(
    private val publishEvent: (com.loopers.domain.order.event.OrderConfirmed) -> Unit,
) : OrderConfirmedPublisher {
    override fun publish(event: com.loopers.domain.order.event.OrderConfirmed) = publishEvent(event)
}

fun main(args: Array<String>) {
    SpringApplicationBuilder(CommerceApiApplication::class.java, Week6Preparation::class.java).run(*args)
}
