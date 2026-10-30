package com.loopers.kafka

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.loopers.application.purchase.PurchaseActivityService
import com.loopers.config.PurchaseKafkaConfiguration
import com.loopers.domain.order.event.OrderConfirmed
import com.loopers.domain.order.event.PurchasedItem
import com.loopers.interfaces.consumer.PurchaseActivityConsumer
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringSerializer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.reset
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.boot.autoconfigure.kafka.KafkaProperties
import org.springframework.kafka.listener.AcknowledgingMessageListener
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PurchaseListenerFailureExperimentTest {
    @Test
    @Timeout(80)
    fun `application failure stops real container without advancing offset and restart completes record`() {
        val topic = "listener-failure-${UUID.randomUUID()}"
        val group = "listener-failure-group-${UUID.randomUUID()}"
        val broker = EmbeddedKafkaKraftBroker(1, 2, topic)
        broker.afterPropertiesSet()
        val properties = KafkaProperties().apply { bootstrapServers = listOf(broker.brokersAsString) }
        val factory = PurchaseKafkaConfiguration().purchaseListenerFactory(properties)
        val container = factory.createContainer(topic)
        val mapper = jacksonObjectMapper().registerModule(JavaTimeModule())
        val service: PurchaseActivityService = mock()
        val listener = PurchaseActivityConsumer(mapper, service)
        val failed = CountDownLatch(1)
        doAnswer {
            failed.countDown()
            error("application storage boundary unavailable")
        }.whenever(service).record(any())
        container.containerProperties.groupId = group
        container.containerProperties.setMessageListener(
            AcknowledgingMessageListener<String, ByteArray> { record, ack ->
            listener.consume(record, checkNotNull(ack))
        },
        )
        try {
            val event = OrderConfirmed(OrderConfirmed.stableEventId(1), 1, Instant.EPOCH, listOf(PurchasedItem(2, 3)), schemaVersion = 1)
            val sent = KafkaProducer<String, ByteArray>(
                mapOf(
                    "bootstrap.servers" to broker.brokersAsString,
                    "key.serializer" to StringSerializer::class.java,
                    "value.serializer" to ByteArraySerializer::class.java,
                    "acks" to "all",
                    "enable.idempotence" to true,
                ),
            ).use { it.send(ProducerRecord(topic, "1", mapper.writeValueAsBytes(event))).get(10, TimeUnit.SECONDS) }
            val partition = TopicPartition(topic, sent.partition())
            container.start()
            check(failed.await(20, TimeUnit.SECONDS))
            val stopDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (container.isRunning && System.nanoTime() < stopDeadline) Thread.sleep(50)
            assertThat(container.isRunning).isFalse()
            AdminClient.create(mapOf("bootstrap.servers" to broker.brokersAsString)).use { admin ->
                val offsets = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS)
                assertThat(offsets[partition]?.offset() ?: sent.offset()).isLessThanOrEqualTo(sent.offset())
                println("WEEK6_LISTENER_FAILURE failedOffset=${sent.offset()} committed=${offsets[partition]?.offset()} stopped=${!container.isRunning}")
                reset(service)
                container.start()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
                var committed: Long? = null
                while (System.nanoTime() < deadline) {
                    committed = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS)[partition]?.offset()
                    if (committed == sent.offset() + 1) break
                    Thread.sleep(100)
                }
                assertThat(committed).isEqualTo(sent.offset() + 1)
                verify(service).record(event)
                println("WEEK6_LISTENER_RECOVERED offset=${sent.offset()} committed=$committed")
            }
        } finally {
            container.stop()
            broker.destroy()
        }
    }
}
