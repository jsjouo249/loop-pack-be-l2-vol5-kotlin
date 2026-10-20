package com.loopers.application.transaction

import com.loopers.application.like.ProductLikeApplicationService
import com.loopers.domain.product.ProductRepository
import com.loopers.infrastructure.like.ProductLikeEntityRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.clearInvocations
import org.mockito.kotlin.any
import org.mockito.kotlin.anyVararg
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ReviewFixRegressionTest : CommerceTransactionTestSupport() {
    @Autowired private lateinit var likes: ProductLikeApplicationService

    @Autowired private lateinit var productRepository: ProductRepository

    @MockitoSpyBean private lateinit var listQuery: com.loopers.domain.product.ProductListQuery

    @MockitoSpyBean private lateinit var likeStorage: ProductLikeEntityRepository

    @Test
    fun `concurrent duplicate likes and unlikes succeed and keep the projection exact`() {
        val buyer = buyer(0)
        val product = product(1000, 5)
        val registrations = race(
            (1..6).map {
                "like-$it" to {
                    likes.like(buyer, product)
                    Unit
                }
            },
        )
        assertOutcomes(registrations, "SUCCESS" to 6)
        assertThat(number("select count(*) from product_likes where product_id=?", product)).isEqualTo(1)
        assertThat(number("select like_count from products where id=?", product)).isEqualTo(1)
        val cancellations = race(
            (1..6).map {
                "unlike-$it" to {
                    likes.unlike(buyer, product)
                    Unit
                }
            },
        )
        assertOutcomes(cancellations, "SUCCESS" to 6)
        assertThat(number("select count(*) from product_likes where product_id=?", product)).isZero()
        assertThat(number("select like_count from products where id=?", product)).isZero()
        assertThat(stock(product)).isEqualTo(5)
    }

    @Test
    fun `different users and mixed requests keep relation count and projection equal`() {
        val product = product(1000, 5)
        val buyers = (1..6).map { buyer(0) }
        val additions = buyers.map {
            "$it" to {
                likes.like(it, product)
                Unit
            }
        }
        assertOutcomes(race(additions), "SUCCESS" to 6)
        assertThat(number("select like_count from products where id=?", product)).isEqualTo(6)
        val mixed = buyers.flatMap { user ->
            listOf(
                "like-$user" to {
                    likes.like(user, product)
                    Unit
                },
                "unlike-$user" to {
                    likes.unlike(user, product)
                    Unit
                },
            )
        }
        assertOutcomes(race(mixed), "SUCCESS" to 12)
        assertThat(number("select like_count from products where id=?", product))
            .isEqualTo(number("select count(*) from product_likes where product_id=?", product))
    }

    @Test
    fun `like relation and projection rollback together after the counter update`() {
        val buyer = buyer(0)
        val product = product(1000, 5)
        doAnswer { invocation ->
            val result = invocation.callRealMethod()
            if (invocation.getArgument<String>(0).startsWith("update products set like_count")) {
                assertThat(number("select like_count from products where id=?", product)).isEqualTo(1)
                throw InjectedStorageFailure()
            }
            result
        }.whenever(writeOperations).update(any(), anyVararg())
        assertThrows<InjectedStorageFailure> { likes.like(buyer, product) }
        tx {
            em.flush()
            em.clear()
            assertThat(number("select count(*) from product_likes where product_id=?", product)).isZero()
            assertThat(number("select like_count from products where id=?", product)).isZero()
        }
    }

    @Test
    fun `unlike rollback restores the relationship and its existing projection`() {
        val buyer = buyer(0)
        val product = product(1000, 5)
        likes.like(buyer, product)
        val before = jdbc.queryForList("select * from product_likes where product_id=?", product)
        doAnswer { invocation ->
            val result = invocation.callRealMethod()
            if (invocation.getArgument<String>(0).startsWith("update products set like_count")) {
                assertThat(number("select like_count from products where id=?", product)).isZero()
                throw InjectedStorageFailure()
            }
            result
        }.whenever(writeOperations).update(any(), anyVararg())
        assertThrows<InjectedStorageFailure> { likes.unlike(buyer, product) }
        tx {
            em.flush()
            em.clear()
            assertThat(jdbc.queryForList("select * from product_likes where product_id=?", product)).isEqualTo(before)
            assertThat(number("select like_count from products where id=?", product)).isEqualTo(1)
        }
    }

    @Test
    fun `product browse keeps one snapshot when brand deletion commits between reads`() {
        val brand = brands.create("브랜드").id
        val product = products.create(brand, "상품", 1000, 5).id
        doAnswer { invocation ->
            val result = invocation.callRealMethod()
            elsewhere { brands.delete(brand) }
            result
        }.whenever(listQuery).findPage(anyOrNull(), any(), any(), any())
        mvc.perform(get("/api/v1/products"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.content[0].id").value(product))
            .andExpect(jsonPath("$.data.content[0].brand.name").value("브랜드"))
        assertThat(number("select count(*) from products where deleted_at is null")).isZero()
    }

    @Test
    fun `my likes keep one snapshot when product deletion commits after the page query`() {
        val buyer = buyer(0)
        val product = product(1000, 5)
        likes.like(buyer, product)
        doAnswer { invocation ->
            val result = invocation.callRealMethod()
            elsewhere { products.delete(product) }
            result
        }.whenever(likeStorage).findActivePageByUser(any(), any(), any())
        mvc.perform(get("/api/v1/users/$buyer/likes").header("X-USER-ID", buyer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.content[0].id").value(product))
        assertThat(number("select count(*) from products where deleted_at is null")).isZero()
    }

    @Test
    fun `first lock uses one select and cached entities are refreshed after another commit`() {
        val product = product(1000, 5)
        tx {
            CapturedSql.statements.clear()
            assertThat(productRepository.findActiveForUpdate(product)!!.stock.remaining).isEqualTo(5)
            assertThat(CapturedSql.statements.filter { it.contains("from products") }).hasSize(1)
            assertThat(productRepository.findActiveForUpdate(Long.MAX_VALUE)).isNull()
        }
        tx {
            assertThat(productRepository.findActive(product)!!.stock.remaining).isEqualTo(5)
            elsewhere { products.adjustStock(product, 9) }
            CapturedSql.statements.clear()
            assertThat(productRepository.findActiveForUpdate(product)!!.stock.remaining).isEqualTo(9)
            assertThat(CapturedSql.statements.filter { it.contains("from products") }).hasSize(1)
        }
    }

    @Test
    fun `brand deletion flushes once for all products and preserves unrelated data`() {
        val brand = brands.create("삭제").id
        repeat(20) { products.create(brand, "상품$it", 1000, it) }
        val untouched = product(2000, 7)
        clearInvocations(writeOperations)
        brands.delete(brand)
        // 상품 묶음 한 번과 브랜드 한 번. 품목 수에 따라 flush 횟수가 늘지 않는다.
        verify(writeOperations, times(2)).flush()
        assertThat(number("select count(*) from products where brand_id=? and deleted_at is not null", brand)).isEqualTo(20)
        assertThat(stock(untouched)).isEqualTo(7)
    }

    @Test
    fun `full product page counts once and popularity sort has no relation aggregation`() {
        val brand = brands.create("브랜드").id
        repeat(4) { products.create(brand, "상품$it", 1000, 5) }
        CapturedSql.statements.clear()
        mvc.perform(get("/api/v1/products?size=2&sort=likes_desc"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.totalElements").value(4))
            .andExpect(jsonPath("$.data.content.length()").value(2))
        assertThat(CapturedSql.statements.filter { it.contains("count(") && it.contains("from products") }).hasSize(1)
        assertThat(CapturedSql.statements).noneMatch { it.contains("group by") || it.contains("join product_likes") || it.contains("for update") }
    }

    private fun elsewhere(action: () -> Unit) {
        val pool = Executors.newSingleThreadExecutor()
        try {
            pool.submit(action).get(30, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
            check(pool.awaitTermination(10, TimeUnit.SECONDS))
        }
    }
}
