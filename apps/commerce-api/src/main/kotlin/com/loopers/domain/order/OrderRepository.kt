package com.loopers.domain.order

interface OrderRepository {
    fun create(order: Order): Order

    fun findForUpdate(id: Long): Order?

    fun saveConfirmation(order: Order): Order

    fun find(id: Long): Order?

    fun findPage(userId: Long?, offset: Int, limit: Int): List<Order>

    fun count(userId: Long?): Long
}
