package com.loopers.application.transaction

import com.loopers.confirmWithTestCard
import com.loopers.domain.commerce.CommerceException
import com.loopers.domain.commerce.CommerceFailure
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AdministrativeConcurrencyTest : CommerceTransactionTestSupport() {
    @Test
    fun `final quantity setting and confirmation have a legal serial result`() {
        val buyer = buyer(10_000)
        val product = product(1000, 5)
        val order = draft(buyer, product)
        observer.events.clear()
        assertOutcomes(
            race(
                listOf(
                    "set" to {
            products.adjustStock(product, 10)
            Unit
        },
            "confirm" to {
            orders.confirmWithTestCard(buyer, order)
            Unit
        },
                ),
            ),
            "SUCCESS" to 2,
        )
        assertThat(stock(product)).isIn(9L, 10L)
        assertThat(balance(buyer)).isEqualTo(10_000)
        println("FINAL_DB adminStock=${stock(product)} balance=${balance(buyer)}")
    }

    @Test
    fun `details and confirmation preserve stock and order price snapshot`() {
        val buyer = buyer(10_000)
        val product = product(1000, 5)
        val order = draft(buyer, product)
        assertOutcomes(
            race(
                listOf(
                    "details" to {
            products.update(product, "변경", 2000)
            Unit
        },
            "confirm" to {
            orders.confirmWithTestCard(buyer, order)
            Unit
        },
                ),
            ),
            "SUCCESS" to 2,
        )
        assertThat(stock(product)).isEqualTo(4)
        assertThat(products.get(product).price).isEqualTo(2000)
        assertThat(orders.getForUser(buyer, order).items.single().unitPrice).isEqualTo(1000)
        assertThat(balance(buyer)).isEqualTo(10_000)
    }

    @Test
    fun `individual deletion and confirmation never restore deleted product`() {
        val buyer = buyer(10_000)
        val product = product(1000, 5)
        val order = draft(buyer, product)
        val outcomes = race(
            listOf(
                "delete" to { products.delete(product) },
                "confirm" to {
            orders.confirmWithTestCard(buyer, order)
            Unit
        },
            ),
        )
        assertThat(outcomes.single { it.id == "delete" }.outcome).isEqualTo("SUCCESS")
        val confirmed = outcomes.single { it.id == "confirm" }.outcome
        assertThat(confirmed).isIn("SUCCESS", "PRODUCT_NOT_FOUND")
        assertThat(number("select count(*) from products where id=? and deleted_at is not null", product)).isEqualTo(1)
        assertThat(stock(product)).isEqualTo(if (confirmed == "SUCCESS") 4 else 5)
        assertThat(balance(buyer)).isEqualTo(10_000)
    }

    @Test
    fun `brand rename and deletion cannot resurrect brand`() {
        val brand = brands.create("브랜드").id
        val product = products.create(brand, "상품", 1000, 0).id
        val outcomes = race(
            listOf(
                "rename" to {
            brands.update(brand, "변경")
            Unit
        },
            "delete" to { brands.delete(brand) },
            ),
        )
        assertThat(outcomes.single { it.id == "rename" }.outcome).isIn("SUCCESS", "BRAND_NOT_FOUND")
        assertThat(outcomes.single { it.id == "delete" }.outcome).isEqualTo("SUCCESS")
        assertThat(number("select count(*) from brands where id=? and deleted_at is not null", brand)).isEqualTo(1)
        assertThat(number("select count(*) from products where id=? and deleted_at is not null", product)).isEqualTo(1)
    }

    @Test
    fun `registration before deletion is included and registration after deletion is rejected`() {
        val brand = brands.create("브랜드").id
        val product = products.create(brand, "먼저 등록", 1000, 1).id
        brands.delete(brand)
        assertThat(number("select count(*) from products where id=? and deleted_at is not null", product)).isEqualTo(1)
        assertThat(assertThrows<CommerceException> { products.create(brand, "늦게 등록", 1000, 1) }.reason).isEqualTo(CommerceFailure.BRAND_NOT_FOUND)
        assertThat(number("select count(*) from products where brand_id=?", brand)).isEqualTo(1)
    }

    @Test
    fun `concurrent registration and deletion leave no active child`() {
        val brand = brands.create("브랜드").id
        val outcomes = race(
            listOf(
                "register" to {
            products.create(brand, "상품", 1000, 1)
            Unit
        },
            "delete" to { brands.delete(brand) },
            ),
        )
        assertThat(outcomes.single { it.id == "register" }.outcome).isIn("SUCCESS", "BRAND_NOT_FOUND")
        assertThat(outcomes.single { it.id == "delete" }.outcome).isEqualTo("SUCCESS")
        assertThat(number("select count(*) from products where brand_id=? and deleted_at is null", brand)).isZero()
        assertThat(number("select count(*) from brands where id=? and deleted_at is not null", brand)).isEqualTo(1)
    }

    @Test
    fun `both serial orders of setting details deletion and confirmation respect their contracts`() {
        for (adminFirst in listOf(true, false)) {
            val buyer = buyer(10_000)
            val product = product(1000, 5)
            val order = draft(buyer, product)
            if (adminFirst) {
                products.adjustStock(product, 10)
                products.update(product, "변경", 2000)
                orders.confirmWithTestCard(buyer, order)
            } else {
                orders.confirmWithTestCard(buyer, order)
                products.adjustStock(product, 10)
                products.update(product, "변경", 2000)
            }
            assertThat(stock(product)).isEqualTo(if (adminFirst) 9 else 10)
            assertThat(balance(buyer)).isEqualTo(10_000)
            assertThat(orders.getForUser(buyer, order).items.single().unitPrice).isEqualTo(1000)
            products.delete(product)
            assertThat(assertThrows<CommerceException> { products.update(product, "복원", 1) }.reason).isEqualTo(CommerceFailure.PRODUCT_NOT_FOUND)
            assertThat(assertThrows<CommerceException> { products.adjustStock(product, 1) }.reason).isEqualTo(CommerceFailure.PRODUCT_NOT_FOUND)
        }
        val buyer = buyer(10_000)
        val product = product(1000, 5)
        val order = draft(buyer, product)
        products.delete(product)
        assertThat(assertThrows<CommerceException> { orders.confirmWithTestCard(buyer, order) }.reason).isEqualTo(CommerceFailure.PRODUCT_NOT_FOUND)
        assertThat(stock(product)).isEqualTo(5)
        assertThat(balance(buyer)).isEqualTo(10_000)
    }

    @Test
    fun `details racing deletion preserve stock and never restore active state`() {
        val product = product(1000, 5)
        val outcomes = race(
            listOf(
            "details" to {
                products.update(product, "변경", 2000)
                Unit
            },
            "delete" to { products.delete(product) },
        ),
        )
        assertThat(outcomes.single { it.id == "delete" }.outcome).isEqualTo("SUCCESS")
        val changed = outcomes.single { it.id == "details" }.outcome
        assertThat(changed).isIn("SUCCESS", "PRODUCT_NOT_FOUND")
        assertThat(stock(product)).isEqualTo(5)
        assertThat(number("select price from products where id=?", product)).isEqualTo(if (changed == "SUCCESS") 2000 else 1000)
        assertThat(number("select count(*) from products where id=? and deleted_at is not null", product)).isEqualTo(1)
    }
}
