package com.loopers.application.transaction

import com.loopers.application.brand.BrandApplicationService
import com.loopers.infrastructure.brand.BrandJpaEntity
import com.loopers.infrastructure.brand.BrandJpaRepository
import com.loopers.infrastructure.product.ProductJpaEntity
import com.loopers.infrastructure.product.ProductJpaRepository
import com.loopers.utils.DatabaseCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class BrandRemovalTransactionTest @Autowired constructor(
    private val service: BrandApplicationService,
    private val brands: BrandJpaRepository,
    private val products: ProductJpaRepository,
    private val cleanUp: DatabaseCleanUp,
) {
    @AfterEach
    fun clean() = cleanUp.truncateAllTables()

    @Test
    fun `brand deletion includes zero stock and preserves other brands`() {
        val brand = brands.save(BrandJpaEntity("삭제"))
        val other = brands.save(BrandJpaEntity("유지"))
        val first = products.save(ProductJpaEntity(brand.id, "상품", 100, 5))
        val zero = products.save(ProductJpaEntity(brand.id, "품절", 200, 0))
        val untouched = products.save(ProductJpaEntity(other.id, "다른 상품", 300, 2))
        service.delete(brand.id)
        assertThat(brands.findById(brand.id).orElseThrow().deletedAt).isNotNull()
        assertThat(products.findById(first.id).orElseThrow().deletedAt).isNotNull()
        assertThat(products.findById(zero.id).orElseThrow().deletedAt).isNotNull()
        assertThat(products.findById(first.id).orElseThrow().stock).isEqualTo(5)
        assertThat(brands.findById(other.id).orElseThrow().deletedAt).isNull()
        assertThat(products.findById(untouched.id).orElseThrow().deletedAt).isNull()
    }

    @Test
    fun `brand with no products is deleted`() {
        val brand = brands.save(BrandJpaEntity("빈 브랜드"))
        service.delete(brand.id)
        assertThat(brands.findById(brand.id).orElseThrow().deletedAt).isNotNull()
    }
}
