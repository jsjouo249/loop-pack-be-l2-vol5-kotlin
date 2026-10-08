package com.loopers.interfaces.api.like

import com.loopers.infrastructure.brand.BrandJpaEntity
import com.loopers.infrastructure.brand.BrandJpaRepository
import com.loopers.domain.like.ProductLike
import com.loopers.domain.like.ProductLikeRepository
import com.loopers.domain.product.ProductRepository
import com.loopers.infrastructure.like.ProductLikeJpaRepository
import com.loopers.infrastructure.product.ProductJpaEntity
import com.loopers.infrastructure.product.ProductJpaRepository
import com.loopers.application.user.UserInitializationService
import com.loopers.infrastructure.user.UserJpaRepository
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.ZonedDateTime

@SpringBootTest
@AutoConfigureMockMvc
class ProductLikeMockMvcTest @Autowired constructor(
    private val mvc: MockMvc,
    private val brands: BrandJpaRepository,
    private val products: ProductJpaRepository,
    private val likes: ProductLikeJpaRepository,
    private val likeRepository: ProductLikeRepository,
    private val productRepository: ProductRepository,
    private val transactionManager: PlatformTransactionManager,
    private val users: UserJpaRepository,
    private val initialization: UserInitializationService,
    private val entityManagerFactory: EntityManagerFactory,
    private val cleanUp: DatabaseCleanUp,
) {
    @AfterEach
    fun clean() = cleanUp.truncateAllTables()

    @Test
    fun `like is idempotent isolated by user and cancellable after product deletion`() {
        val user1 = initialization.create("사용자1")
        val user2 = initialization.create("사용자2")
        val brand = brands.save(BrandJpaEntity("브랜드"))
        val product = products.save(ProductJpaEntity(brand.id, "상품", 100, 1))
        repeat(2) {
            mvc.perform(post("/api/v1/products/${product.id}/likes").header("X-USER-ID", user1.id))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.likeCount").value(1))
        }
        mvc.perform(post("/api/v1/products/${product.id}/likes").header("X-USER-ID", user2.id))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.likeCount").value(2))
        assertThat(likes.countByProductId(product.id)).isEqualTo(2)
        mvc.perform(get("/api/v1/users/${user1.id}/likes").header("X-USER-ID", user2.id))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.meta.errorCode").value("USER_MISMATCH"))
        mvc.perform(get("/api/v1/users/${user1.id}/likes").header("X-USER-ID", user1.id))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.totalElements").value(1))

        product.delete()
        products.saveAndFlush(product)
        mvc.perform(get("/api/v1/users/${user1.id}/likes").header("X-USER-ID", user1.id))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.totalElements").value(0))
        mvc.perform(post("/api/v1/products/${product.id}/likes").header("X-USER-ID", user1.id))
            .andExpect(status().isNotFound)
        repeat(2) {
            mvc.perform(delete("/api/v1/products/${product.id}/likes").header("X-USER-ID", user1.id))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.liked").value(false))
        }
        assertThat(likes.countByProductId(product.id)).isEqualTo(1)
    }

    @Test
    fun `private like requires a valid existing requester`() {
        mvc.perform(post("/api/v1/products/1/likes"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.meta.errorCode").value("AUTHENTICATION_REQUIRED"))
        mvc.perform(post("/api/v1/products/1/likes").header("X-USER-ID", "abc"))
            .andExpect(status().isBadRequest)
        mvc.perform(post("/api/v1/products/1/likes").header("X-USER-ID", 999))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.meta.errorCode").value("USER_NOT_FOUND"))
    }

    @Test
    fun `like page reads only active rows needed for requested page`() {
        val user = initialization.create("사용자")
        val brand = brands.save(BrandJpaEntity("브랜드"))
        val likedAt = ZonedDateTime.now().minusDays(1)
        val productIds = (0 until 30).map { index ->
            val product = products.save(ProductJpaEntity(brand.id, "상품$index", 100, 1))
            TransactionTemplate(transactionManager).execute {
                checkNotNull(productRepository.findActiveForUpdate(product.id))
                likeRepository.createIfAbsent(ProductLike(user.id, product.id, likedAt = likedAt.plusSeconds(index.toLong())))
            }
            product.id
        }
        val deleted = products.findById(productIds.last()).orElseThrow()
        deleted.delete()
        products.saveAndFlush(deleted)

        val statistics = entityManagerFactory.unwrap(SessionFactory::class.java).statistics
        val statisticsWereEnabled = statistics.isStatisticsEnabled
        statistics.isStatisticsEnabled = true
        try {
            statistics.clear()
            mvc.perform(get("/api/v1/users/${user.id}/likes?size=2").header("X-USER-ID", user.id))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.totalElements").value(29))
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.content[0].id").value(productIds[28]))
                .andExpect(jsonPath("$.data.content[1].id").value(productIds[27]))
            assertThat(statistics.prepareStatementCount).isLessThanOrEqualTo(10)
            mvc.perform(get("/api/v1/users/${user.id}/likes?page=1&size=2").header("X-USER-ID", user.id))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.totalElements").value(29))
                .andExpect(jsonPath("$.data.content[0].id").value(productIds[26]))
                .andExpect(jsonPath("$.data.content[1].id").value(productIds[25]))
            statistics.clear()
            mvc.perform(get("/api/v1/users/${user.id}/likes?size=20").header("X-USER-ID", user.id))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.content.length()").value(20))
            assertThat(statistics.prepareStatementCount).isLessThanOrEqualTo(10)
        } finally {
            statistics.isStatisticsEnabled = statisticsWereEnabled
        }
    }
}
