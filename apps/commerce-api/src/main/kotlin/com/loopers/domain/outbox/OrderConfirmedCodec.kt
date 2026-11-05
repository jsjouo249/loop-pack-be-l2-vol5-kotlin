package com.loopers.domain.outbox

import com.loopers.domain.order.event.OrderConfirmed

interface OrderConfirmedCodec {
    fun encode(event: OrderConfirmed): String
    fun decode(payload: String): OrderConfirmed
}
