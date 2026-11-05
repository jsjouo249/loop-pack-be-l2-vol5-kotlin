package com.loopers.infrastructure.outbox

import com.loopers.domain.outbox.BrokerReceipt
import com.loopers.domain.outbox.OutboxFact
import com.loopers.domain.outbox.OutboxSender
import jakarta.annotation.PreDestroy
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.boot.autoconfigure.kafka.KafkaProperties
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

@Component
class KafkaOutboxSender(
    properties: KafkaProperties,
    @Value("\${outbox.relay.send-timeout-ms:12000}") private val timeoutMillis: Long,
) : OutboxSender {
    init {
        require(timeoutMillis > 0)
    }

    private val factory = DefaultKafkaProducerFactory<String, String>(
        HashMap(properties.buildProducerProperties()).apply {
            put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java)
            put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java)
            put(ProducerConfig.ACKS_CONFIG, "all")
            put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true)
        },
    )
    private val kafka = KafkaTemplate(factory)

    override fun send(fact: OutboxFact): BrokerReceipt {
        val metadata = kafka.send(fact.topic, fact.messageKey, fact.payload)
            .get(timeoutMillis, TimeUnit.MILLISECONDS).recordMetadata

        return BrokerReceipt(metadata.topic(), metadata.partition(), metadata.offset())
    }

    @PreDestroy
    fun close() = factory.destroy()
}
