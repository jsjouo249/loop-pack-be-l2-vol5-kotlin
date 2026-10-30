package com.loopers.interfaces.consumer

import com.loopers.config.kafka.KafkaConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty

@Component
@ConditionalOnProperty(name = ["demo-kafka.enabled"], havingValue = "true")
class DemoKafkaConsumer {
    @KafkaListener(
        topics = ["\${demo-kafka.test.topic-name}"],
        containerFactory = KafkaConfig.BATCH_LISTENER,
    )
    fun demoListener(
        messages: List<ConsumerRecord<Any, Any>>,
        acknowledgment: Acknowledgment,
    ) {
        println(messages)
        acknowledgment.acknowledge() // manual ack
    }
}
