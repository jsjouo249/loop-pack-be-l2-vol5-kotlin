package com.loopers.infrastructure.purchase

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Index
import org.hibernate.annotations.Check
import java.io.Serializable
import java.time.Instant

@Entity
@Table(name = "purchase_receipts")
@Check(constraints = "order_id > 0")
class PurchaseReceiptJpaEntity(
    @Id @Column(name = "order_id") val orderId: Long,
    @Column(nullable = false, length = 64) val fingerprint: String,
    @Column(name = "confirmed_at", nullable = false) val confirmedAt: Instant,
)

@Entity
@Table(name = "purchase_event_receipts")
@Check(constraints = "order_id > 0")
class PurchaseEventReceiptJpaEntity(
    @Id @Column(name = "event_id", length = 36) val eventId: String,
    @Column(name = "order_id", nullable = false) val orderId: Long,
)

@Embeddable
data class PurchaseActivityId(
    @Column(name = "order_id") val orderId: Long = 0,
    @Column(name = "product_id") val productId: Long = 0,
) : Serializable

@Entity
@Table(name = "purchase_activities", indexes = [Index(columnList = "confirmed_at,product_id")])
@Check(constraints = "order_id > 0 and product_id > 0 and quantity > 0")
class PurchaseActivityJpaEntity(
    @EmbeddedId val id: PurchaseActivityId,
    @Column(nullable = false) val quantity: Int,
    @Column(name = "confirmed_at", nullable = false) val confirmedAt: Instant,
)
