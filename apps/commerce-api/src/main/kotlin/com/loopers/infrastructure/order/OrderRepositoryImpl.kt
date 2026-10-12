package com.loopers.infrastructure.order

import com.loopers.domain.order.Order
import com.loopers.domain.order.OrderLine
import com.loopers.domain.order.OrderRepository
import com.loopers.domain.commerce.EntityState
import org.springframework.stereotype.Repository

@Repository
class OrderRepositoryImpl(private val repository: OrderEntityRepository) : OrderRepository {
    override fun create(order: Order): Order = repository.create(order.toEntity()).toDomain()

    override fun findForUpdate(id: Long): Order? = repository.findForUpdate(id)?.toDomain()

    override fun saveConfirmation(order: Order): Order = repository.saveConfirmation(
        OrderConfirmation(
            order.id,
            order.status,
            requireNotNull(order.paymentAmount).amount,
            requireNotNull(order.paymentResult),
            requireNotNull(order.confirmedAt),
        ),
    ).toDomain()

    override fun find(id: Long): Order? = repository.find(id)?.toDomain()

    override fun findPage(userId: Long?, offset: Int, limit: Int): List<Order> =
        repository.findPage(userId, offset, limit).map { it.toDomain() }

    override fun count(userId: Long?): Long = repository.count(userId)

    private fun Order.toEntity() = OrderEntity(
        userId,
        items.map { OrderItemEntity(it.productId, it.quantity, it.unitPrice.amount, it.lineAmount.amount, it.id) },
        totalAmount.amount,
        status,
        paymentAmount?.amount,
        paymentResult,
        confirmedAt,
        EntityState(id, createdAt, updatedAt),
    )

    private fun OrderEntity.toDomain() = Order.reconstitute(
        userId,
        items.map { OrderLine(it.productId, it.quantity, it.unitPrice, it.id) },
        state,
        status,
        paymentAmount,
        paymentResult,
        confirmedAt,
    )
}
