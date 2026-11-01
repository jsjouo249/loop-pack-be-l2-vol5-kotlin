package com.loopers.domain.purchase

import com.loopers.domain.order.event.OrderConfirmed
import com.loopers.domain.order.event.PurchasedItem
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID

class PurchaseReceiptTest {
    private val event = OrderConfirmed(OrderConfirmed.stableEventId(1), 1, Instant.EPOCH, listOf(PurchasedItem(2, 3)), schemaVersion = 1)

    @Test fun `alternate delivery identity is accepted but changed facts conflict`() {
        val receipt = PurchaseReceipt.from(event)
        receipt.assertSameFact(event.copy(eventId = UUID.randomUUID().toString()))

        assertThrows<PurchaseEventConflict> { receipt.assertSameFact(event.copy(items = listOf(PurchasedItem(2, 4)))) }
        assertThrows<PurchaseEventConflict> { receipt.assertSameFact(event.copy(confirmedAt = Instant.ofEpochSecond(1))) }
        assertThrows<PurchaseEventConflict> { receipt.assertSameFact(event.copy(orderId = 3)) }
    }
}
