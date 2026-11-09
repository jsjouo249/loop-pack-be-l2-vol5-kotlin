package com.loopers.application.outbox

import com.loopers.domain.commerce.EntityState
import com.loopers.domain.inventory.StockReservationRepository
import com.loopers.domain.inventory.StockReservation
import com.loopers.domain.order.Order
import com.loopers.domain.order.OrderLine
import com.loopers.domain.order.OrderRepository
import com.loopers.domain.order.OrderStatus
import com.loopers.domain.order.PaymentMethod
import com.loopers.domain.order.PaymentResult
import com.loopers.domain.payment.AttemptStatus
import com.loopers.domain.payment.PaymentAttempt
import com.loopers.domain.payment.PaymentAttemptRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.time.ZonedDateTime

class OutboxRecoveryTest {
    @ParameterizedTest
    @ValueSource(strings = ["payment-result", "payment-amount", "attempt-amount", "attempt-user", "confirmed-at", "point"])
    fun `incomplete or conflicting source facts cannot create an Outbox`(conflict: String) {
        val orders = mock<OrderRepository>()
        val attempts = mock<PaymentAttemptRepository>()
        val reservations = mock<StockReservationRepository>()
        val outbox = mock<OrderConfirmedOutbox>()
        val order = Order.reconstitute(
            1,
            listOf(OrderLine(7, 2, 100)),
            EntityState(id = 11),
            OrderStatus.CONFIRMED,
            if (conflict == "payment-amount") 199 else 200,
            if (conflict == "payment-result") null else PaymentResult.SUCCESS,
            if (conflict == "confirmed-at") null else ZonedDateTime.parse("2026-11-05T00:00:00Z"),
            if (conflict == "point") PaymentMethod.POINT else PaymentMethod.PG,
        )
        val attempt = PaymentAttempt(
            11,
            if (conflict == "attempt-user") 2 else 1,
            "attempt-11",
            if (conflict == "attempt-amount") 199 else 200,
            "LP-ORD-0000000011",
            initialStatus = AttemptStatus.SUCCESS,
            initialTransactionKey = "TX-1",
        )
        whenever(orders.findForUpdate(11)).thenReturn(order)
        whenever(attempts.findForUpdate(11)).thenReturn(attempt)
        whenever(reservations.findForUpdate(11)).thenReturn(listOf(StockReservation(11, 7, 2).apply { consume() }))

        assertThrows<IllegalStateException> { OutboxRecovery(orders, attempts, reservations, outbox).restore(11) }

        verifyNoInteractions(outbox)
        assertThat(order.status).isEqualTo(OrderStatus.CONFIRMED)
        assertThat(attempt.status).isEqualTo(AttemptStatus.SUCCESS)
    }
}
