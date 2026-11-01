package com.loopers.application.order

import com.loopers.domain.order.event.OrderConfirmed
import com.loopers.domain.order.event.OrderConfirmedPublisher
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

@Component
class OrderConfirmedAfterCommit(private val publisher: OrderConfirmedPublisher) {
    private val log = LoggerFactory.getLogger(javaClass)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun publish(event: OrderConfirmed) {
        try {
            publisher.publish(event)
        } catch (failure: Exception) {
            // The order has committed. Never retry payment or translate publication failure into payment failure.
            log.error("order_confirmed publication unresolved eventId={} orderId={}", event.eventId, event.orderId, failure)
        }
    }
}
