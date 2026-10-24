package com.loopers.infrastructure.order

import com.loopers.domain.commerce.EntityState
import com.loopers.domain.order.OrderStatus
import com.loopers.domain.order.PaymentResult
import com.loopers.domain.order.PaymentMethod
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.DynamicUpdate
import org.hibernate.annotations.Check
import java.time.ZonedDateTime

@Entity
@DynamicUpdate
@Table(name = "orders", indexes = [Index(columnList = "user_id,created_at,id"), Index(columnList = "created_at,id")])
@Check(constraints = "total_amount >= 0 and (payment_amount is null or payment_amount >= 0)")
class OrderJpaEntity(
    @Column(name = "user_id", nullable = false)
    val userId: Long,
    @Column(name = "total_amount", nullable = false)
    val totalAmount: Long,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: OrderStatus = OrderStatus.DRAFT

    @OneToMany(mappedBy = "order", cascade = [CascadeType.PERSIST], fetch = FetchType.LAZY)
    val items: MutableList<OrderItemJpaEntity> = mutableListOf()

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", length = 20)
    var paymentMethod: PaymentMethod? = null

    @Column(name = "payment_amount")
    var paymentAmount: Long? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_result", length = 20)
    var paymentResult: PaymentResult? = null

    @Column(name = "confirmed_at")
    var confirmedAt: ZonedDateTime? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: ZonedDateTime
        private set

    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: ZonedDateTime
        private set

    fun addLine(item: OrderItemEntity) {
        items.add(OrderItemJpaEntity(this, item.productId, item.quantity, item.unitPrice, item.lineAmount))
    }

    fun toEntity(): OrderEntity = OrderEntity(
        userId,
        items.map { OrderItemEntity(it.productId, it.quantity, it.unitPrice, it.lineAmount, it.id) },
        totalAmount,
        status,
        paymentAmount,
        paymentResult,
        confirmedAt,
        EntityState(id, createdAt, updatedAt),
        paymentMethod,
    )

    @PrePersist
    private fun prePersist() {
        val now = ZonedDateTime.now()
        createdAt = now
        updatedAt = now
    }

    @PreUpdate
    private fun preUpdate() {
        updatedAt = ZonedDateTime.now()
    }
}

@Entity
@Table(name = "order_items", uniqueConstraints = [UniqueConstraint(columnNames = ["order_id", "product_id"])])
@Check(constraints = "quantity > 0 and unit_price >= 0 and line_amount >= 0")
class OrderItemJpaEntity(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    val order: OrderJpaEntity,
    @Column(name = "product_id", nullable = false)
    val productId: Long,
    @Column(nullable = false)
    val quantity: Int,
    @Column(name = "unit_price", nullable = false)
    val unitPrice: Long,
    @Column(name = "line_amount", nullable = false)
    val lineAmount: Long,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0
}
