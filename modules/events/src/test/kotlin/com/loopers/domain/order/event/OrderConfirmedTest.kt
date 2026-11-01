package com.loopers.domain.order.event

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID

class OrderConfirmedTest {
    private val event = OrderConfirmed(
        OrderConfirmed.stableEventId(1),
        1,
        Instant.parse("2026-10-28T00:00:00Z"),
        listOf(PurchasedItem(2, 3), PurchasedItem(4, 5)),
        schemaVersion = 1,
    )

    @Test
    fun `same order with different quantity is a different fact`() {
        assertThat(event.fingerprint()).isNotEqualTo(event.copy(items = listOf(PurchasedItem(2, 4))).fingerprint())
    }

    @Test
    fun `transport identity and item order do not change the fact`() {
        assertThat(event.fingerprint()).isEqualTo(event.copy(eventId = UUID.randomUUID().toString(), items = event.items.reversed()).fingerprint())
        assertThat(OrderConfirmed.stableEventId(1)).isEqualTo(event.eventId)
        assertThat(OrderConfirmed.stableEventId(2)).isNotEqualTo(event.eventId)
    }

    @Test
    fun `invalid version identifiers quantities and duplicate products fail before storage`() {
        assertThrows<IllegalArgumentException> { event.copy(schemaVersion = 2) }
        assertThrows<IllegalArgumentException> { event.copy(eventId = "bad") }
        assertThrows<IllegalArgumentException> { event.copy(orderId = 0) }
        assertThrows<IllegalArgumentException> { PurchasedItem(0, 1) }
        assertThrows<IllegalArgumentException> { PurchasedItem(2, 0) }
        assertThrows<IllegalArgumentException> { event.copy(items = emptyList()) }
        assertThrows<IllegalArgumentException> { event.copy(items = listOf(PurchasedItem(2, 1), PurchasedItem(2, 2))) }
    }
}
