package com.loopers.application.transaction

import com.loopers.application.brand.BrandApplicationService
import com.loopers.application.order.OrderApplicationService
import com.loopers.domain.brand.Brand
import com.loopers.domain.brand.BrandRepository
import com.loopers.domain.commerce.EntityState
import com.loopers.domain.order.Order
import com.loopers.domain.order.OrderLine
import com.loopers.domain.order.OrderRepository
import com.loopers.domain.order.OrderStatus
import com.loopers.domain.point.PointAccount
import com.loopers.domain.point.PointAccountRepository
import com.loopers.domain.product.InsufficientStockException
import com.loopers.domain.product.Product
import com.loopers.domain.product.ProductRepository
import com.loopers.domain.user.User
import com.loopers.domain.user.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

// 애플리케이션의 협력 순서를 검증한다. 실제 잠금과 롤백은 별도 MySQL 통합 테스트에서 확인한다.
class AggregateCoordinationTest {
    private val brands: BrandRepository = mock()
    private val products: ProductRepository = mock()
    private val orders: OrderRepository = mock()
    private val users: UserRepository = mock()
    private val points: PointAccountRepository = mock()
    private val attempt = object : WriteTransactionAttempt() {
        override fun <T> execute(state: AttemptState, action: () -> T): T = action()
    }
    private val executor = RetryingWriteExecutor(attempt, RetryTime(), WriteObserver())

    @Test
    fun `brand removal locks every child first then invokes domain deletion including zero stock`() {
        val brand = Brand.reconstitute("브랜드", EntityState(id = 1))
        val first = Product.reconstitute(1, "첫 상품", 1000, 5, EntityState(id = 2))
        val zero = Product.reconstitute(1, "품절", 1000, 0, EntityState(id = 3))
        whenever(brands.findActiveForUpdate(1)).thenReturn(brand)
        whenever(products.findActiveIdsByBrandId(1)).thenReturn(listOf(2, 3))
        whenever(products.findActiveForUpdate(2)).thenReturn(first)
        whenever(products.findActiveForUpdate(3)).thenReturn(zero)
        BrandApplicationService(brands, products, executor, mock()).delete(1)
        inOrder(brands, products) {
            verify(brands).findActiveForUpdate(1)
            verify(products).findActiveIdsByBrandId(1)
            verify(products).findActiveForUpdate(2)
            verify(products).findActiveForUpdate(3)
            verify(products).markDeletedAll(listOf(first, zero))
            verify(brands).markDeleted(brand)
        }
        assertThat(listOf(first.deletedAt, zero.deletedAt, brand.deletedAt)).doesNotContainNull()
        assertThat(first.stock.remaining).isEqualTo(5)
        assertThat(zero.stock.remaining).isZero()
    }

    @Test
    fun `order facade delegates PG confirmation and returns its snapshot without point access`() {
        val order = setupOrder(5)
        order.startPayment()
        order.finishPayment(true)
        val payments: com.loopers.application.payment.PaymentApplicationService = mock()
        val attempts: com.loopers.domain.payment.PaymentAttemptRepository = mock()
        val input = com.loopers.testPaymentInput(4)
        whenever(payments.confirm(1, 4, input)).thenReturn(com.loopers.application.payment.PaymentSnapshot(order, null))
        val result = OrderApplicationService(orders, products, users, payments, attempts).confirm(1, 4, input)
        assertThat(result.status).isEqualTo(OrderStatus.CONFIRMED)
        assertThat(result.paymentAmount).isEqualTo(3000)
        assertThat(result.items.map { it.unitPrice }).containsExactly(1000, 2000)
        org.mockito.kotlin.verifyNoInteractions(points)
    }

    @Test
    fun `order facade propagates PG precondition failure without changing snapshots`() {
        val order = setupOrder(0)
        val payments: com.loopers.application.payment.PaymentApplicationService = mock()
        val input = com.loopers.testPaymentInput(4)
        whenever(payments.confirm(1, 4, input)).thenThrow(InsufficientStockException())
        assertThrows<InsufficientStockException> { OrderApplicationService(orders, products, users, payments, mock()).confirm(1, 4, input) }
        verify(products, never()).saveStockChanges(any())
        verify(points, never()).saveBalance(any())
        assertThat(order.status).isEqualTo(OrderStatus.DRAFT)
    }

    private fun setupOrder(secondStock: Int): Order {
        val order = Order.reconstitute(1, listOf(OrderLine(3, 1, 2000), OrderLine(2, 1, 1000)), EntityState(id = 4), OrderStatus.DRAFT, null, null, null)
        whenever(users.find(1)).thenReturn(User.reconstitute("구매자", EntityState(id = 1)))
        whenever(orders.findForUpdate(4)).thenReturn(order)
        whenever(points.findByUserIdForUpdate(1)).thenReturn(PointAccount.reconstitute(1, 10_000))
        whenever(products.findActiveForUpdate(2)).thenReturn(Product.reconstitute(1, "첫", 3000, 5, EntityState(id = 2)))
        whenever(products.findActiveForUpdate(3)).thenReturn(Product.reconstitute(1, "둘", 4000, secondStock, EntityState(id = 3)))
        return order
    }
}
