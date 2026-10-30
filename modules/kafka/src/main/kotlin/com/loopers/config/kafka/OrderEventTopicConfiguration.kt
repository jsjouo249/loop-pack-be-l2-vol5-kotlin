package com.loopers.config.kafka

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.TopicBuilder
import org.springframework.kafka.core.KafkaAdmin

@Configuration
class OrderEventTopicConfiguration {
    @Bean
    fun orderEventTopics(
        @Value("\${purchase.kafka.topic:order-confirmed-v1}") topic: String,
    ): KafkaAdmin.NewTopics = KafkaAdmin.NewTopics(
        TopicBuilder.name(topic).partitions(2).replicas(1).config("min.insync.replicas", "1").build(),
    )
}
