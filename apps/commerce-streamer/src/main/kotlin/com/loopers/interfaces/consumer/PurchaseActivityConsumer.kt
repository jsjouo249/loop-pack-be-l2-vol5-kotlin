package com.loopers.interfaces.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import com.loopers.application.purchase.PurchaseActivityService
import com.loopers.domain.order.event.OrderConfirmed
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component

@Component
class PurchaseActivityConsumer(private val mapper: ObjectMapper, private val purchases: PurchaseActivityService) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        id = "purchase-activity",
        topics = ["\${purchase.kafka.topic:order-confirmed-v1}"],
        groupId = "\${purchase.kafka.group:purchase-activity-v1}",
        containerFactory = "purchaseListenerFactory",
        autoStartup = "\${purchase.consumer.auto-startup:true}",
    )
    fun consume(record: ConsumerRecord<String, ByteArray>, acknowledgment: Acknowledgment) {
        try {
            val event = mapper.readValue(record.value(), OrderConfirmed::class.java)
            require(record.key() == event.orderId.toString()) { "Kafka key must match orderId" }

            purchases.record(event)

            acknowledgment.acknowledge()

            log.info(
                "purchase_activity completed eventId={} orderId={} topic={} partition={} offset={}",
                event.eventId,
                event.orderId,
                record.topic(),
                record.partition(),
                record.offset(),
            )
        } catch (failure: Exception) {
            log.error(
                "purchase_activity failed topic={} partition={} offset={}; consumer will stop",
                record.topic(),
                record.partition(),
                record.offset(),
                failure,
            )

            throw failure
        }
    }
}
