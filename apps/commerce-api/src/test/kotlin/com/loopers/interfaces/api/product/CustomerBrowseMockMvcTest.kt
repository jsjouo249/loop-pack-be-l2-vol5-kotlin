package com.loopers.interfaces.api.product

import com.loopers.infrastructure.brand.BrandJpaEntity
import com.loopers.infrastructure.brand.BrandJpaRepository
import com.loopers.domain.like.ProductLike
import com.loopers.domain.like.ProductLikeRepository
import com.loopers.domain.product.ProductRepository
import com.loopers.infrastructure.product.ProductJpaEntity
import com.loopers.infrastructure.product.ProductJpaRepository
import com.loopers.utils.DatabaseCleanUp
import jakarta.persistence.EntityManagerFactory
import org.assertj.core.api.Assertions.assertThat
import org.hibernate.SessionFactory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
@AutoConfigureMockMvc
class CustomerBrowseMockMvcTest @Autowired constructor(
    private val mvc: MockMvc,
    private val brands: BrandJpaRepository,
    private val products: ProductJpaRepository,
    private val likes: ProductLikeRepository,
    private val productRepository: ProductRepository,
    private val transactionManager: PlatformTransactionManager,
    private val entityManagerFactory: EntityManagerFactory,
    private val cleanUp: DatabaseCleanUp,
) {
    private fun like(userId: Long, productId: Long) = TransactionTemplate(transactionManager).execute {
        checkNotNull(productRepository.findActiveForUpdate(productId))
        likes.createIfAbsent(ProductLike(userId, productId))
    }

    @AfterEach
    fun clean() = cleanUp.truncateAllTables()

    @Test
    fun `public browse filters pages and sorts active products`() {
        val brand = brands.save(BrandJpaEntity("브랜드"))
        val expensive = products.save(ProductJpaEntity(brand.id, "비싼 상품", 9000, 1))
        val popular = products.save(ProductJpaEntity(brand.id, "인기 상품", 1000, 0))
        like(1, popular.id)
        like(2, popular.id)
        like(1, expensive.id)

        mvc.perform(get("/api/v1/brands/${brand.id}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.name").value("브랜드"))
        mvc.perform(get("/api/v1/products?brandId=${brand.id}&sort=likes_desc&size=1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.totalElements").value(2))
            .andExpect(jsonPath("$.data.content[0].id").value(popular.id))
            .andExpect(jsonPath("$.data.content[0].likeCount").value(2))
        mvc.perform(get("/api/v1/products?sort=price_asc"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.content[0].id").value(popular.id))
        mvc.perform(get("/api/v1/products/${expensive.id}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.brand.id").value(brand.id))
        mvc.perform(get("/api/v1/products?sort=unknown"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.meta.errorCode").value("UNSUPPORTED_SORT"))
        mvc.perform(get("/api/v1/products?brandId=999"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.totalElements").value(0))
        mvc.perform(get("/api/v1/products?page=-1"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.meta.errorCode").value("INVALID_REQUEST"))

        expensive.delete()
        products.saveAndFlush(expensive)
        mvc.perform(get("/api/v1/products/${expensive.id}"))
            .andExpect(status().isNotFound)
        mvc.perform(get("/api/v1/products?sort=likes_desc"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.totalElements").value(1))
    }

    @Test
    fun `product page assembles brands and like counts with bounded queries`() {
        val brand = brands.save(BrandJpaEntity("브랜드"))
        repeat(15) { index -> products.save(ProductJpaEntity(brand.id, "상품$index", 100, 1)) }
        val statistics = entityManagerFactory.unwrap(SessionFactory::class.java).statistics
        val statisticsWereEnabled = statistics.isStatisticsEnabled
        statistics.isStatisticsEnabled = true
        try {
            statistics.clear()
            mvc.perform(get("/api/v1/products?size=15"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.content.length()").value(15))
                .andExpect(jsonPath("$.data.content[0].brand.name").value("브랜드"))
                .andExpect(jsonPath("$.data.content[0].likeCount").value(0))
            assertThat(statistics.prepareStatementCount).isLessThanOrEqualTo(8)
        } finally {
            statistics.isStatisticsEnabled = statisticsWereEnabled
        }
    }

    @Test
    fun `likes sort keeps brand filter and products without likes`() {
        val firstBrand = brands.save(BrandJpaEntity("첫 브랜드"))
        val secondBrand = brands.save(BrandJpaEntity("두번째 브랜드"))
        val unliked = products.save(ProductJpaEntity(firstBrand.id, "좋아요 없음", 100, 1))
        val liked = products.save(ProductJpaEntity(firstBrand.id, "좋아요 있음", 100, 1))
        val otherBrandProduct = products.save(ProductJpaEntity(secondBrand.id, "다른 브랜드 상품", 100, 1))
        val newerUnliked = products.save(ProductJpaEntity(firstBrand.id, "새 상품", 100, 1))
        like(1, liked.id)
        repeat(3) { index -> like(index + 1L, otherBrandProduct.id) }

        mvc.perform(get("/api/v1/products?brandId=${firstBrand.id}&sort=likes_desc"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.totalElements").value(3))
            .andExpect(jsonPath("$.data.content[0].id").value(liked.id))
            .andExpect(jsonPath("$.data.content[0].likeCount").value(1))
            .andExpect(jsonPath("$.data.content[1].id").value(newerUnliked.id))
            .andExpect(jsonPath("$.data.content[2].id").value(unliked.id))
            .andExpect(jsonPath("$.data.content[2].likeCount").value(0))
        mvc.perform(get("/api/v1/products?brandId=${firstBrand.id}&sort=price_asc"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.content[0].id").value(newerUnliked.id))
            .andExpect(jsonPath("$.data.content[1].id").value(liked.id))
            .andExpect(jsonPath("$.data.content[2].id").value(unliked.id))
        mvc.perform(get("/api/v1/products?brandId=${firstBrand.id}&sort=latest"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.content[0].id").value(newerUnliked.id))
    }
}
