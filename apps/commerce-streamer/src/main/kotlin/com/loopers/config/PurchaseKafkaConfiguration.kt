package com.loopers.config

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.springframework.boot.autoconfigure.kafka.KafkaProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler
import org.springframework.kafka.listener.ContainerProperties

@Configuration
class PurchaseKafkaConfiguration {
    @Bean
    fun purchaseListenerFactory(properties: KafkaProperties): ConcurrentKafkaListenerContainerFactory<String, ByteArray> {
        val config = HashMap(properties.buildConsumerProperties()).apply {
            put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java)
            put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java)
            put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
            put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
            put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 100)
        }

        return ConcurrentKafkaListenerContainerFactory<String, ByteArray>().apply {
            consumerFactory = DefaultKafkaConsumerFactory(config)
            containerProperties.ackMode = ContainerProperties.AckMode.MANUAL_IMMEDIATE
            containerProperties.isSyncCommits = true
            setConcurrency(2)
            setCommonErrorHandler(CommonContainerStoppingErrorHandler())
        }
    }
}
