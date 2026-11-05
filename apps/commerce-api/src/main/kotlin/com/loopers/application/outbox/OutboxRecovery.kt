package com.loopers.application.outbox

import com.loopers.domain.inventory.ReservationStatus
import com.loopers.domain.inventory.StockReservationRepository
import com.loopers.domain.order.OrderRepository
import com.loopers.domain.payment.AttemptStatus
import com.loopers.domain.payment.PaymentAttemptRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class OutboxRecovery(
    private val orders: OrderRepository,
    private val attempts: PaymentAttemptRepository,
    private val reservations: StockReservationRepository,
    private val outbox: OrderConfirmedOutbox,
) {
    @Transactional
    fun restore(orderId: Long): String {
        val order = checkNotNull(orders.findForUpdate(orderId)) { "Missing orderId=$orderId" }
        val attempt = checkNotNull(attempts.findForUpdate(orderId)) { "Missing PG fact orderId=$orderId" }
        val held = reservations.findForUpdate(orderId)
        val event = order.confirmedPurchase()
        check(
            attempt.status == AttemptStatus.SUCCESS && !attempt.quarantined && !attempt.transactionKey.isNullOrBlank() &&
                attempt.orderId == order.id && attempt.userId == order.userId && attempt.amount == order.totalAmount.amount,
        ) {
            "Incomplete confirmation orderId=$orderId"
        }
        check(
            held.size == order.items.size && held.zip(order.items).all { (reservation, item) ->
            reservation.status == ReservationStatus.CONSUMED &&
                reservation.productId == item.productId && reservation.quantity == item.quantity
        },
        ) { "Inconsistent reservations orderId=$orderId" }
        outbox.restore(event)

        return event.eventId
    }
}
