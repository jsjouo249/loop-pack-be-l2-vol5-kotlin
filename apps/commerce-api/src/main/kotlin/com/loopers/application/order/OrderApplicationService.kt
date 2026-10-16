package com.loopers.application.order

import com.loopers.application.common.PageResult
import com.loopers.application.common.offset
import com.loopers.application.common.pageResult
import com.loopers.application.common.positiveId
import com.loopers.domain.commerce.CommerceException
import com.loopers.domain.commerce.CommerceFailure
import com.loopers.domain.order.Order
import com.loopers.domain.order.OrderLine
import com.loopers.domain.order.OrderRepository
import com.loopers.domain.order.OrderStatus
import com.loopers.domain.order.PaymentResult
import com.loopers.domain.point.PointAccountRepository
import com.loopers.domain.product.ProductRepository
import com.loopers.domain.user.UserRepository
import com.loopers.application.product.ProductListInvalidation
import com.loopers.application.transaction.RetryingWriteExecutor
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.ZonedDateTime

data class OrderItemResult(val productId: Long, val quantity: Int, val unitPrice: Long, val lineAmount: Long)

data class OrderResult(
    val id: Long,
    val userId: Long,
    val status: OrderStatus,
    val items: List<OrderItemResult>,
    val totalAmount: Long,
    val paymentAmount: Long?,
    val paymentResult: PaymentResult?,
    val createdAt: ZonedDateTime,
    val confirmedAt: ZonedDateTime?,
)

data class OrderItemInput(val productId: Long, val quantity: Int)

@Service
class OrderApplicationService(
    private val orders: OrderRepository,
    private val products: ProductRepository,
    private val users: UserRepository,
    private val points: PointAccountRepository,
    private val writes: RetryingWriteExecutor,
    private val invalidation: ProductListInvalidation,
) {
    fun create(userId: Long, inputs: List<OrderItemInput>): OrderResult {
        requireUser(userId)
        if (inputs.isEmpty()) throw CommerceException(CommerceFailure.INVALID_REQUEST)
        // 중복 상품 수량을 합치되 정수 범위를 넘는 요청은 거절하고, 상품 ID 순서를 고정한다.
        val quantities = sortedMapOf<Long, Int>()
        inputs.forEach { input ->
            positiveId(input.productId)
            if (input.quantity <= 0) throw CommerceException(CommerceFailure.INVALID_QUANTITY)
            quantities[input.productId] = try {
                Math.addExact(quantities[input.productId] ?: 0, input.quantity)
            } catch (_: ArithmeticException) {
                throw CommerceException(CommerceFailure.INVALID_QUANTITY)
            }
        }
        val found = products.findActiveByIds(quantities.keys).associateBy { it.id }
        if (found.size != quantities.size) throw CommerceException(CommerceFailure.PRODUCT_NOT_FOUND)
        val lines = quantities.map { (id, quantity) -> OrderLine(id, quantity, found.getValue(id).price.amount) }
        return result(orders.create(Order(userId, lines)))
    }

    /** 주문·포인트·상품을 잠근 뒤 전체 조건을 검증하고, 재고·잔액·확정 결과를 함께 저장한다. */
    fun confirm(userId: Long, orderId: Long): OrderResult = writes.execute("order.confirm") {
        requireUser(userId)
        positiveId(orderId)
        // 잠금 순서는 주문 → 포인트 계정 → 상품 ID 오름차순이다. Order.items가 상품 ID 순서를 보장한다.
        val order = orders.findForUpdate(orderId) ?: throw CommerceException(CommerceFailure.ORDER_NOT_FOUND)
        if (order.userId != userId) throw CommerceException(CommerceFailure.ORDER_NOT_OWNED)
        order.assertDraft()
        // 0원 주문도 기존 계정이 필요하며, 누락 계정을 잔액 0으로 간주하거나 자동 생성하지 않는다.
        val pointAccount = checkNotNull(points.findByUserIdForUpdate(userId)) { "Missing point account: $userId" }
        val orderProducts = order.items.map { item ->
            products.findActiveForUpdate(item.productId) ?: throw CommerceException(CommerceFailure.PRODUCT_NOT_FOUND)
        }
        // 주문 생성 이후 삭제·재고 변경을 다시 검증한다. 가격은 주문 생성 시의 스냅샷을 사용한다.
        order.items.zip(orderProducts).forEach { (item, product) -> product.assertCanDecrease(item.quantity) }
        pointAccount.assertCanPay(order.totalAmount.amount)
        // 모든 조건을 확인한 후 변경하며, 뒤 저장이 실패하면 앞서 실행한 SQL까지 함께 롤백한다.
        order.items.zip(orderProducts).forEach { (item, product) -> product.decreaseStock(item.quantity) }
        products.saveStockChanges(orderProducts)
        pointAccount.pay(order.totalAmount.amount)
        points.saveBalance(pointAccount)
        order.confirm()
        result(orders.saveConfirmation(order)).also { invalidation.afterCommit() }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun getForUser(userId: Long, orderId: Long): OrderResult {
        requireUser(userId)
        positiveId(orderId)
        val order = orders.find(orderId) ?: throw CommerceException(CommerceFailure.ORDER_NOT_FOUND)
        if (order.userId != userId) throw CommerceException(CommerceFailure.ORDER_NOT_OWNED)
        return result(order)
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun listForUser(userId: Long, page: Int, size: Int): PageResult<OrderResult> {
        requireUser(userId)
        val offset = offset(page, size)
        return pageResult(page, size, orders.count(userId), orders.findPage(userId, offset, size).map(::result))
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun getForAdmin(orderId: Long): OrderResult {
        positiveId(orderId)
        return result(orders.find(orderId) ?: throw CommerceException(CommerceFailure.ORDER_NOT_FOUND))
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun listForAdmin(userId: Long?, page: Int, size: Int): PageResult<OrderResult> {
        if (userId != null) positiveId(userId)
        val offset = offset(page, size)
        return pageResult(page, size, orders.count(userId), orders.findPage(userId, offset, size).map(::result))
    }

    private fun requireUser(id: Long) {
        positiveId(id)
        users.find(id) ?: throw CommerceException(CommerceFailure.USER_NOT_FOUND)
    }

    private fun result(order: Order) = OrderResult(
        order.id,
        order.userId,
        order.status,
        order.items.map { OrderItemResult(it.productId, it.quantity, it.unitPrice.amount, it.lineAmount.amount) },
        order.totalAmount.amount,
        order.paymentAmount?.amount,
        order.paymentResult,
        order.createdAt,
        order.confirmedAt,
    )
}
