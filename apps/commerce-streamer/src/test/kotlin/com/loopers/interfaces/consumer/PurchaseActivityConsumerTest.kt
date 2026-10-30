package com.loopers.interfaces.consumer

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.loopers.application.purchase.PurchaseActivityService
import com.loopers.domain.order.event.OrderConfirmed
import com.loopers.domain.order.event.PurchasedItem
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.kafka.support.Acknowledgment
import java.time.Instant

class PurchaseActivityConsumerTest {
    private val mapper = jacksonObjectMapper().registerModule(JavaTimeModule())
    private val service: PurchaseActivityService = mock()
    private val ack: Acknowledgment = mock()
    private val listener = PurchaseActivityConsumer(mapper, service)
    private val event = OrderConfirmed(OrderConfirmed.stableEventId(1), 1, Instant.EPOCH, listOf(PurchasedItem(2, 3)), schemaVersion = 1)

    @Test fun `acknowledges only after DB service returns successfully`() {
        listener.consume(ConsumerRecord("topic", 0, 5, "1", mapper.writeValueAsBytes(event)), ack)

        inOrder(service, ack) {
            verify(service).record(event)
            verify(ack).acknowledge()
        }
    }

    @Test fun `DB exception propagates and never acknowledges`() {
        doThrow(IllegalStateException("DB failure")).whenever(service).record(any())

        assertThrows<IllegalStateException> { listener.consume(ConsumerRecord("topic", 0, 5, "1", mapper.writeValueAsBytes(event)), ack) }

        verifyNoInteractions(ack)
    }

    @Test fun `bad key JSON and absent version cannot reach storage or ack`() {
        assertThrows<IllegalArgumentException> { listener.consume(ConsumerRecord("topic", 0, 5, "2", mapper.writeValueAsBytes(event)), ack) }
        assertThrows<Exception> { listener.consume(ConsumerRecord("topic", 0, 5, "1", "{".toByteArray()), ack) }
        val absentVersion = mapper.valueToTree<com.fasterxml.jackson.databind.node.ObjectNode>(event).apply { remove("schemaVersion") }
        assertThrows<Exception> { listener.consume(ConsumerRecord("topic", 0, 5, "1", mapper.writeValueAsBytes(absentVersion)), ack) }

        verifyNoInteractions(service, ack)
    }
}
