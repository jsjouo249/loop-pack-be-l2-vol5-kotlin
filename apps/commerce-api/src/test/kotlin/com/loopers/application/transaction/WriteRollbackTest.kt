package com.loopers.application.transaction

import com.loopers.application.order.OrderItemInput
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.anyVararg
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.whenever

class WriteRollbackTest : CommerceTransactionTestSupport() {
    @Test
    fun `brand deletion rolls back after first product SQL and failure at next save`() {
        val brand = brands.create("삭제")
        products.create(brand.id, "첫 상품", 1000, 5)
        products.create(brand.id, "품절 상품", 2000, 0)
        product(3000, 2)
        val before = snapshot()
        var calls = 0
        doAnswer { invocation ->
            val sql = invocation.getArgument<String>(0)
            if (sql.startsWith("update products set deleted_at")) calls++
            if (sql.startsWith("update products set deleted_at") && calls == 2) {
                // 첫 상품의 삭제 SQL까지 확인한 뒤 두 번째 저장에서 실패시켜 부분 반영을 검사한다.
                em.flush()
                assertThat(number("select count(*) from products where deleted_at is not null")).isEqualTo(1)
                throw InjectedStorageFailure()
            }
            invocation.callRealMethod()
        }.whenever(writeOperations).update(any(), anyVararg())
        assertThrows<InjectedStorageFailure> { brands.delete(brand.id) }
        assertThat(calls).isEqualTo(2)
        assertThat(tx { snapshot() }).isEqualTo(before)
    }

    @Test
    fun `order rollback restores products points draft payment and immutable lines after SQL`() {
        val buyer = buyer(10_000)
        val first = product(1000, 5)
        val second = product(2000, 3)
        val order = orders.create(buyer, listOf(OrderItemInput(first, 2), OrderItemInput(second, 1))).id
        val before = snapshot()
        var observedSql = false
        doAnswer {
            // 재고와 잔액의 실제 SQL 반영을 확인하고 주문 확정 저장 직전에 실패시킨다.
            em.flush()
            assertThat(stock(first)).isEqualTo(3)
            assertThat(stock(second)).isEqualTo(2)
            assertThat(balance(buyer)).isEqualTo(6000)
            observedSql = true
            throw InjectedStorageFailure()
        }.whenever(orderStorage).saveConfirmation(any())
        assertThrows<InjectedStorageFailure> { orders.confirm(buyer, order) }
        assertThat(observedSql).isTrue()
        assertThat(tx { snapshot() }).isEqualTo(before)
    }

    @Test
    fun `user and account creation are one transaction even after user insert`() {
        var observedSql = false
        doAnswer {
            em.flush()
            assertThat(number("select count(*) from users")).isEqualTo(1)
            observedSql = true
            throw InjectedStorageFailure()
        }.whenever(pointStorage).create(any())
        assertThrows<InjectedStorageFailure> { users.create("실패") }
        assertThat(observedSql).isTrue()
        tx {
            em.flush()
            em.clear()
            assertThat(number("select count(*) from users")).isZero()
            assertThat(number("select count(*) from point_balances")).isZero()
        }
    }

    // 실패 전후 전체 행을 비교해 잔액·재고뿐 아니라 시각·주문 품목·관련 없는 데이터도 보존되는지 확인한다.
    private fun snapshot(): Map<String, List<Map<String, Any>>> = listOf(
        "brands",
        "products",
        "users",
        "point_balances",
        "orders",
        "order_items",
        "product_likes",
    ).associateWith { table -> jdbc.queryForList("select * from $table order by 1") }
}

class InjectedStorageFailure : RuntimeException("Test storage boundary failure")
