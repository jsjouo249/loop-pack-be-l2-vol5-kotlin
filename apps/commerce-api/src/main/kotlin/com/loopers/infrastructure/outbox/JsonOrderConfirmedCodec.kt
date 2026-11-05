package com.loopers.infrastructure.outbox

import com.fasterxml.jackson.databind.ObjectMapper
import com.loopers.domain.order.event.OrderConfirmed
import com.loopers.domain.outbox.OrderConfirmedCodec
import org.springframework.stereotype.Component

@Component
class JsonOrderConfirmedCodec(private val mapper: ObjectMapper) : OrderConfirmedCodec {
    override fun encode(event: OrderConfirmed): String = mapper.writeValueAsString(event)

    override fun decode(payload: String): OrderConfirmed = mapper.readValue(payload, OrderConfirmed::class.java)
}
