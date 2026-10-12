package com.loopers.infrastructure.order

import com.loopers.domain.commerce.EntityState
import com.loopers.domain.order.OrderStatus
import com.loopers.domain.order.PaymentResult
import java.time.ZonedDateTime

data class OrderItemEntity(
    val productId: Long,
    val quantity: Int,
    val unitPrice: Long,
    val lineAmount: Long,
    val id: Long = 0,
)

data class OrderEntity(
    val userId: Long,
    val items: List<OrderItemEntity>,
    val totalAmount: Long,
    val status: OrderStatus,
    val paymentAmount: Long?,
    val paymentResult: PaymentResult?,
    val confirmedAt: ZonedDateTime?,
    val state: EntityState,
)

data class OrderConfirmation(
    val id: Long,
    val status: OrderStatus,
    val paymentAmount: Long,
    val paymentResult: PaymentResult,
    val confirmedAt: ZonedDateTime,
)

interface OrderEntityRepository {
    fun create(entity: OrderEntity): OrderEntity

    fun findForUpdate(id: Long): OrderEntity?

    fun saveConfirmation(change: OrderConfirmation): OrderEntity

    fun find(id: Long): OrderEntity?

    fun findPage(userId: Long?, offset: Int, limit: Int): List<OrderEntity>

    fun count(userId: Long?): Long
}
