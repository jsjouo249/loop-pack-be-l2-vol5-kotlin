package com.loopers.infrastructure.purchase

import com.loopers.domain.order.event.OrderConfirmed
import com.loopers.domain.purchase.PurchaseActivityRepository
import com.loopers.domain.purchase.PurchaseReceipt
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.stereotype.Repository

@Repository
class JpaPurchaseActivityRepository(@PersistenceContext private val em: EntityManager) : PurchaseActivityRepository {
    override fun findReceipt(orderId: Long): PurchaseReceipt? = em.find(PurchaseReceiptJpaEntity::class.java, orderId)?.let {
        PurchaseReceipt(it.orderId, it.fingerprint, it.confirmedAt)
    }

    override fun findEventOrderId(eventId: String): Long? = em.find(PurchaseEventReceiptJpaEntity::class.java, eventId)?.orderId

    override fun insertReceipt(receipt: PurchaseReceipt) {
        em.persist(PurchaseReceiptJpaEntity(receipt.orderId, receipt.fingerprint, receipt.confirmedAt))
    }

    override fun insertEvent(eventId: String, orderId: Long) {
        em.persist(PurchaseEventReceiptJpaEntity(eventId, orderId))
    }

    override fun insertActivities(event: OrderConfirmed) {
        event.items.forEach { item ->
            em.persist(
                PurchaseActivityJpaEntity(PurchaseActivityId(event.orderId, item.productId), item.quantity, event.confirmedAt),
            )
        }
    }
}
