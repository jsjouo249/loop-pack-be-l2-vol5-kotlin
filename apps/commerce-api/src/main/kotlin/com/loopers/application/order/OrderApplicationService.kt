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
import com.loopers.application.payment.PaymentApplicationService
import com.loopers.application.payment.PaymentInput
import com.loopers.domain.payment.PaymentAttempt
import com.loopers.domain.payment.PaymentAttemptRepository
import com.loopers.domain.payment.AttemptStatus
import com.loopers.domain.order.PaymentMethod
import com.loopers.domain.product.ProductRepository
import com.loopers.domain.user.UserRepository
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
    val paymentMethod: PaymentMethod?,
    val paymentAttempt: PaymentAttemptResult?,
)

data class PaymentAttemptResult(
    val paymentAttemptKey: String,
    val status: AttemptStatus,
    val amount: Long,
    val providerOrderId: String,
    val transactionKey: String?,
    val lastCheck: String?,
    val quarantined: Boolean,
)

data class OrderItemInput(val productId: Long, val quantity: Int)

@Service
class OrderApplicationService(
    private val orders: OrderRepository,
    private val products: ProductRepository,
    private val users: UserRepository,
    private val payments: PaymentApplicationService,
    private val attempts: PaymentAttemptRepository,
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

    fun confirm(userId: Long, orderId: Long, input: PaymentInput? = null): OrderResult {
        val snapshot = payments.confirm(userId, orderId, input)
        return result(snapshot.order, snapshot.attempt)
    }

    fun reconcile(userId: Long, orderId: Long): OrderResult {
        val snapshot = payments.reconcile(userId, orderId)
        return result(snapshot.order, snapshot.attempt)
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
        return pageResult(page, size, orders.count(userId), results(orders.findPage(userId, offset, size)))
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
        return pageResult(page, size, orders.count(userId), results(orders.findPage(userId, offset, size)))
    }

    private fun requireUser(id: Long) {
        positiveId(id)
        users.find(id) ?: throw CommerceException(CommerceFailure.USER_NOT_FOUND)
    }

    private fun results(found: List<Order>): List<OrderResult> {
        val byOrder = attempts.findAll(found.map { it.id }).associateBy { it.orderId }
        return found.map { result(it, byOrder[it.id]) }
    }

    private fun result(order: Order, attempt: PaymentAttempt? = attempts.find(order.id)) = OrderResult(
        order.id,
        order.userId,
        order.status,
        order.items.map { OrderItemResult(it.productId, it.quantity, it.unitPrice.amount, it.lineAmount.amount) },
        order.totalAmount.amount,
        order.paymentAmount?.amount,
        order.paymentResult,
        order.createdAt,
        order.confirmedAt,
        order.paymentMethod,
        attempt?.let {
            PaymentAttemptResult(
                it.key,
                it.status,
                it.amount,
                it.providerOrderId,
                it.transactionKey,
                it.lastCheck,
                it.quarantined,
            )
        },
    )
}
