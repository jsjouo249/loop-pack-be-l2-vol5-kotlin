package com.loopers.domain.order.event

import java.time.Instant
import java.util.UUID
import java.security.MessageDigest

data class PurchasedItem(val productId: Long, val quantity: Int) {
    init {
        require(productId > 0 && quantity > 0) { "Product and quantity must be positive" }
    }
}

data class OrderConfirmed(
    val eventId: String,
    val orderId: Long,
    val confirmedAt: Instant,
    val items: List<PurchasedItem>,
    val schemaVersion: Int,
) {
    init {
        require(UUID.fromString(eventId).toString() == eventId) { "eventId must be a canonical UUID" }
        require(orderId > 0) { "orderId must be positive" }
        require(schemaVersion == 1) { "Unsupported OrderConfirmed schema" }
        require(items.isNotEmpty() && items.map { it.productId }.distinct().size == items.size) {
            "Each purchased product must occur exactly once"
        }
    }

    fun fingerprint(): String {
        val canonical = "$schemaVersion|$orderId|$confirmedAt|" + items.sortedBy { it.productId }
            .joinToString(";") { "${it.productId}:${it.quantity}" }

        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    companion object {
        // One logical confirmation per order; transport retries and schema changes do not create a new identity.
        fun stableEventId(orderId: Long): String = UUID.nameUUIDFromBytes(
            "com.loopers.OrderConfirmed:$orderId".toByteArray(Charsets.UTF_8),
        ).toString()
    }
}

interface OrderConfirmedPublisher {
    /** Returns only after the broker acknowledges; failure does not imply the broker received nothing. */
    fun publish(event: OrderConfirmed)
}
