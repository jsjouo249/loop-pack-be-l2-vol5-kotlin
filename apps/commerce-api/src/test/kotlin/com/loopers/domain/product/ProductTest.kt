package com.loopers.domain.product

import com.loopers.domain.commerce.CommerceException
import com.loopers.domain.commerce.CommerceFailure
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ProductTest {
    @Test
    fun `uses Stock value object and keeps brand and stock when details change`() {
        val product = Product(1, " 상품 ", 1000, 5)
        assertThat(product.name).isEqualTo("상품")
        product.decreaseStock(2)
        assertThat(product.stock.remaining).isEqualTo(3)
        product.changeDetails("변경", 2000)
        assertThat(product.brandId).isEqualTo(1)
        assertThat(product.stock.remaining).isEqualTo(3)
        assertThat(product.price.amount).isEqualTo(2000)
    }

    @Test
    fun `invalid new details preserve existing state`() {
        val product = Product(1, "상품", 1000, 5)
        assertThat(assertThrows<CommerceException> { product.changeDetails("변경", -1) }.reason).isEqualTo(CommerceFailure.INVALID_PRICE)
        assertThat(product.name).isEqualTo("상품")
        assertThat(product.price.amount).isEqualTo(1000)
        assertThat(assertThrows<CommerceException> { product.adjustStock(-1) }.reason).isEqualTo(CommerceFailure.INVALID_STOCK)
        assertThat(product.stock.remaining).isEqualTo(5)
    }

    @Test
    fun `deleted product rejects changes without modifying its values`() {
        val product = Product(1, "상품", 1000, 5)
        product.delete()
        listOf<() -> Unit>(
            { product.changeDetails("변경", 2000) },
            { product.adjustStock(10) },
            { product.decreaseStock(1) },
            { product.assertCanDecrease(1) },
        ).forEach { action ->
            assertThat(assertThrows<CommerceException>(action).reason).isEqualTo(CommerceFailure.PRODUCT_NOT_FOUND)
        }
        assertThat(product.name).isEqualTo("상품")
        assertThat(product.price.amount).isEqualTo(1000)
        assertThat(product.stock.remaining).isEqualTo(5)
    }
}
