package com.loopers.domain.order

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PgOrderTest {
    @Test
    fun `PG acceptance waits for final result and preserves price snapshot`() {
        val order = Order(1, listOf(OrderLine(2, 2, 1000)))
        order.startPayment()
        assertThat(order.status).isEqualTo(OrderStatus.PAYMENT_PENDING)
        assertThat(order.paymentAmount).isNull()
        order.finishPayment(true)
        assertThat(order.status).isEqualTo(OrderStatus.CONFIRMED)
        assertThat(order.paymentAmount!!.amount).isEqualTo(2000)
        assertThat(order.paymentMethod).isEqualTo(PaymentMethod.PG)
    }
}
