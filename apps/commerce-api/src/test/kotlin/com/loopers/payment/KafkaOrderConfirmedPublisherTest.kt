package com.loopers.payment

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.loopers.domain.order.event.OrderConfirmed
import com.loopers.domain.order.event.PurchasedItem
import com.loopers.infrastructure.order.KafkaOrderConfirmedPublisher
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.serializer.JsonSerializer
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

class KafkaOrderConfirmedPublisherTest {
    @Test
    @Timeout(60)
    fun `broker receives order key and snapshot and publication metric is independent of consumption`() {
        val topic = "publisher-${UUID.randomUUID()}"
        val broker = EmbeddedKafkaKraftBroker(1, 2, topic)
        broker.afterPropertiesSet()
        val factory = DefaultKafkaProducerFactory<Any, Any>(
            mapOf(
                "bootstrap.servers" to broker.brokersAsString,
                "key.serializer" to StringSerializer::class.java,
                "value.serializer" to JsonSerializer::class.java,
                "acks" to "all",
                "enable.idempotence" to true,
                "retries" to 3,
                "max.in.flight.requests.per.connection" to 5,
                "spring.json.add.type.headers" to false,
            ),
        )
        try {
            val meters = SimpleMeterRegistry()
            val publisher = KafkaOrderConfirmedPublisher(KafkaTemplate(factory), meters, topic)
            val event = OrderConfirmed(OrderConfirmed.stableEventId(1), 1, Instant.parse("2026-10-28T00:00:00Z"), listOf(PurchasedItem(10, 2)), schemaVersion = 1)
            publisher.publish(event)
            // No consumer has run: broker acknowledgement alone already increments this metric.
            assertThat(meters.get("order.confirmed.publish").tag("result", "acknowledged").counter().count()).isEqualTo(1.0)
            KafkaConsumer<String, ByteArray>(
                mapOf(
                    "bootstrap.servers" to broker.brokersAsString,
                    "group.id" to "verify-${UUID.randomUUID()}",
                    "key.deserializer" to StringDeserializer::class.java,
                    "value.deserializer" to ByteArrayDeserializer::class.java,
                    "enable.auto.commit" to false,
                    "auto.offset.reset" to "earliest",
                ),
            ).use { consumer ->
                consumer.subscribe(listOf(topic))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
                var received = false
                while (System.nanoTime() < deadline && !received) {
                    for (record in consumer.poll(Duration.ofMillis(200))) {
                        assertThat(record.key()).isEqualTo("1")
                        assertThat(jacksonObjectMapper().registerModule(JavaTimeModule()).readValue(record.value(), OrderConfirmed::class.java)).isEqualTo(event)
                        println("WEEK6_PRODUCER topic=${record.topic()} partition=${record.partition()} offset=${record.offset()} key=${record.key()} eventId=${event.eventId}")
                        received = true
                    }
                }
                assertThat(received).isTrue()
            }
        } finally {
            factory.destroy()
            broker.destroy()
        }
    }

    @Test
    @Timeout(15)
    fun `unreachable broker reports unresolved publication without claiming non-delivery`() {
        val factory = DefaultKafkaProducerFactory<Any, Any>(
            mapOf(
                "bootstrap.servers" to "127.0.0.1:1",
                "key.serializer" to StringSerializer::class.java,
                "value.serializer" to JsonSerializer::class.java,
                "max.block.ms" to 300,
                "request.timeout.ms" to 300,
                "delivery.timeout.ms" to 600,
                "linger.ms" to 0,
            ),
        )
        try {
            val meters = SimpleMeterRegistry()
            val publisher = KafkaOrderConfirmedPublisher(KafkaTemplate(factory), meters, "missing")
            val event = OrderConfirmed(OrderConfirmed.stableEventId(1), 1, Instant.EPOCH, listOf(PurchasedItem(10, 2)), schemaVersion = 1)
            assertThrows<Exception> { publisher.publish(event) }
            assertThat(meters.get("order.confirmed.publish").tag("result", "unresolved").counter().count()).isEqualTo(1.0)
            assertThat(meters.find("order.confirmed.publish").tag("result", "acknowledged").counter()).isNull()
        } finally {
            factory.destroy()
        }
    }
}
