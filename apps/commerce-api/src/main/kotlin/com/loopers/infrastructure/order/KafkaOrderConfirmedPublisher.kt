package com.loopers.infrastructure.order

import com.loopers.domain.order.event.OrderConfirmed
import com.loopers.domain.order.event.OrderConfirmedPublisher
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

@Component
class KafkaOrderConfirmedPublisher(
    private val kafka: KafkaTemplate<Any, Any>,
    private val meters: MeterRegistry,
    @Value("\${purchase.kafka.topic:order-confirmed-v1}") private val topic: String,
) : OrderConfirmedPublisher {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun publish(event: OrderConfirmed) {
        try {
            val metadata = kafka.send(topic, event.orderId.toString(), event).get(12, TimeUnit.SECONDS).recordMetadata

            meters.counter("order.confirmed.publish", "result", "acknowledged").increment()
            log.info(
                "order_confirmed broker_ack eventId={} orderId={} topic={} partition={} offset={}",
                event.eventId,
                event.orderId,
                metadata.topic(),
                metadata.partition(),
                metadata.offset(),
            )
        } catch (failure: Exception) {
            if (failure is InterruptedException) Thread.currentThread().interrupt()

            meters.counter("order.confirmed.publish", "result", "unresolved").increment()

            throw failure
        }
    }
}
