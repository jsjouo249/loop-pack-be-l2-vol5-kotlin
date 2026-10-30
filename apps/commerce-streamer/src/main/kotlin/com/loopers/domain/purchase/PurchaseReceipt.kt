package com.loopers.domain.purchase

import com.loopers.domain.order.event.OrderConfirmed
import java.time.Instant

class PurchaseEventConflict(message: String) : RuntimeException(message)

data class PurchaseReceipt(val orderId: Long, val fingerprint: String, val confirmedAt: Instant) {
    fun assertSameFact(event: OrderConfirmed) {
        if (orderId != event.orderId || fingerprint != event.fingerprint()) {
            throw PurchaseEventConflict("Conflicting confirmation eventId=${event.eventId} orderId=${event.orderId}")
        }
    }

    companion object {
        fun from(event: OrderConfirmed) = PurchaseReceipt(event.orderId, event.fingerprint(), event.confirmedAt)
    }
}

interface PurchaseActivityRepository {
    fun findReceipt(orderId: Long): PurchaseReceipt?
    fun findEventOrderId(eventId: String): Long?
    fun insertReceipt(receipt: PurchaseReceipt)
    fun insertEvent(eventId: String, orderId: Long)
    fun insertActivities(event: OrderConfirmed)
}
