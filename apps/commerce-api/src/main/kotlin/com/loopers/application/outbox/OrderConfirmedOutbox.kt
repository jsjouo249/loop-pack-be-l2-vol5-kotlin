package com.loopers.application.outbox

import com.loopers.domain.order.event.OrderConfirmed
import com.loopers.domain.outbox.OrderConfirmedCodec
import com.loopers.domain.outbox.OutboxFact
import com.loopers.domain.outbox.OutboxMessage
import com.loopers.domain.outbox.OutboxRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
class OrderConfirmedOutbox(
    private val repository: OutboxRepository,
    private val codec: OrderConfirmedCodec,
    private val clock: Clock,
    @Value("\${purchase.kafka.topic:order-confirmed-v1}") private val topic: String,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun record(event: OrderConfirmed) {
        repository.insert(OutboxMessage(fact(event), clock.instant()))
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun restore(event: OrderConfirmed) {
        val previous = repository.find(event.eventId)
        if (previous == null) {
            record(event)
        } else {
            check(
                previous.fact.orderId == event.orderId && previous.fact.eventType == "OrderConfirmed" &&
                previous.fact.schemaVersion == event.schemaVersion && previous.fact.topic == topic &&
                previous.fact.messageKey == event.orderId.toString() && codec.decode(previous.fact.payload) == event,
            ) {
                "Outbox conflict eventId=${event.eventId} orderId=${event.orderId}"
            }
        }
    }

    private fun fact(event: OrderConfirmed) = OutboxFact(
        event.eventId,
        event.orderId,
        "OrderConfirmed",
        event.schemaVersion,
        topic,
        event.orderId.toString(),
        codec.encode(event),
    )
}
