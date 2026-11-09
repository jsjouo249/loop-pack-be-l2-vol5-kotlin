package com.loopers.payment

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.loopers.domain.order.event.OrderConfirmed
import com.loopers.domain.order.event.PurchasedItem
import com.loopers.domain.outbox.OutboxFact
import com.loopers.infrastructure.outbox.KafkaOutboxSender
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.boot.autoconfigure.kafka.KafkaProperties
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Timeout(30)
class KafkaOutboxSenderTest {
    @Test
    fun `actual broker stores original JSON and same identity at separate offsets`() {
        val topic = "outbox-sender-${UUID.randomUUID()}"
        val broker = EmbeddedKafkaKraftBroker(1, 2, topic)
        broker.afterPropertiesSet()
        val properties = KafkaProperties().apply { bootstrapServers = listOf(broker.brokersAsString) }
        val sender = KafkaOutboxSender(properties, 12_000)
        try {
            val mapper = jacksonObjectMapper().findAndRegisterModules()
            val event = OrderConfirmed(
                OrderConfirmed.stableEventId(1),
                1,
                Instant.parse("2026-11-05T00:00:00.123456Z"),
                listOf(PurchasedItem(7, 2), PurchasedItem(8, 1)),
                1,
            )
            val fact = OutboxFact(event.eventId, 1, "OrderConfirmed", 1, topic, "1", mapper.writeValueAsString(event))
            val first = sender.send(fact)
            val second = sender.send(fact)
            val config = mapOf<String, Any>(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to broker.brokersAsString,
                ConsumerConfig.GROUP_ID_CONFIG to UUID.randomUUID().toString(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            )
            KafkaConsumer<String, String>(config).use { consumer ->
                consumer.subscribe(listOf(topic))
                val records = mutableListOf<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>>()
                val deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos()
                while (records.size < 2 && System.nanoTime() < deadline) {
                    consumer.poll(Duration.ofMillis(100)).forEach { records.add(it) }
                }

                assertThat(records).hasSize(2)
                assertThat(records.map { it.key() }).containsOnly("1")
                assertThat(records.map { it.value() }).containsOnly(fact.payload)
                assertThat(records.map { mapper.readValue(it.value(), OrderConfirmed::class.java) }).containsOnly(event)
                assertThat(first.partition).isEqualTo(second.partition)
                assertThat(records.map { it.offset() }).containsExactly(first.offset, second.offset).doesNotHaveDuplicates()
                println("WEEK7_BROKER eventId=${event.eventId} key=1 partition=${first.partition} offsets=${first.offset},${second.offset} immutableJson=true")
            }
        } finally {
            sender.close()
            broker.destroy()
        }
    }
}
