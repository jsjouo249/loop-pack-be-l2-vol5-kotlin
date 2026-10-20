package com.loopers.application.cache

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.loopers.application.like.ProductLikeApplicationService
import com.loopers.application.product.CustomerProductQueryService
import com.loopers.application.product.ProductListInvalidation
import com.loopers.application.transaction.CapturedSql
import com.loopers.application.transaction.CommerceTransactionTestSupport
import com.loopers.domain.product.ProductListCache
import com.loopers.domain.product.ProductListCacheRead
import com.loopers.domain.product.ProductListKey
import com.loopers.domain.product.ProductListQuery
import com.loopers.domain.product.ProductListSnapshot
import com.loopers.domain.product.ProductSort
import io.lettuce.core.ReadFrom
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.clearInvocations
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doCallRealMethod
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.dao.CannotAcquireLockException
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.sql.SQLException
import java.time.Duration
import java.time.ZonedDateTime

class ProductListCacheContractTest : CommerceTransactionTestSupport() {
    @MockitoSpyBean private lateinit var cache: ProductListCache

    @MockitoSpyBean private lateinit var queries: ProductListQuery

    @MockitoSpyBean private lateinit var invalidation: ProductListInvalidation

    @Autowired private lateinit var customer: CustomerProductQueryService

    @Autowired private lateinit var likes: ProductLikeApplicationService

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var meters: MeterRegistry

    @Autowired
    @Qualifier("redisTemplateMaster")
    private lateinit var redis: RedisTemplate<String, String>

    @Autowired
    @Qualifier("redisConnectionMaster")
    private lateinit var factory: LettuceConnectionFactory

    private fun browse(query: String = ""): JsonNode {
        val response = mvc.perform(get("/api/v1/products$query")).andExpect(status().isOk).andReturn().response
        return mapper.readTree(response.contentAsString)["data"]
    }

    @Test
    fun `validation precedes cache access and normalized keys are isolated`() {
        val brand = brands.create("브랜드").id
        products.create(brand, "상품", 100, 5)
        clearInvocations(cache)
        listOf("?page=-1", "?size=0", "?size=101", "?page=2147483647&size=2", "?brandId=0", "?sort=unknown").forEach {
            mvc.perform(get("/api/v1/products$it")).andExpect(status().isBadRequest)
        }
        verify(cache, never()).read(any())
        val variants = listOf("", "?sort=price_asc", "?sort=likes_desc", "?page=1", "?size=1", "?brandId=$brand")
        variants.forEach { browse(it) }
        val keys = redis.keys("products:v1:list:*")!!
        assertThat(keys).hasSize(6).contains(
            "products:v1:list:brand=all:sort=latest:page=0:size=20",
            "products:v1:list:brand=$brand:sort=latest:page=0:size=20",
        )
    }

    @Test
    fun `actual master connection has both 200ms timeouts and stored TTL is 30 seconds without sliding`() {
        product(100, 3)
        browse()
        val key = redis.keys("products:v1:list:*")!!.single()
        val before = redis.getExpire(key, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertThat(before).isBetween(28000L, 30000L)
        Thread.sleep(80)
        browse()
        assertThat(redis.getExpire(key, java.util.concurrent.TimeUnit.MILLISECONDS)).isLessThan(before)
        assertThat(factory.clientConfiguration.readFrom.get()).isEqualTo(ReadFrom.MASTER)
        assertThat(factory.clientConfiguration.commandTimeout).isEqualTo(Duration.ofMillis(200))
        assertThat(factory.clientConfiguration.clientOptions.get().socketOptions.connectTimeout).isEqualTo(Duration.ofMillis(200))
    }

    @Test
    fun `snapshot roundtrip preserves zoned timestamps and empty pages`() {
        product(100, 3)
        val result = customer.list(null, 0, 20, "latest")
        val first = (cache.read(ProductListKey(null, ProductSort.LATEST, 0, 20)) as ProductListCacheRead.Hit).snapshot
        val timestamp = ZonedDateTime.parse("2026-10-15T10:12:13.123456+09:00[Asia/Seoul]")
        val snapshot = first.copy(content = first.content.map { it.copy(createdAt = timestamp) })
        val key = ProductListKey(null, ProductSort.LATEST, 0, 20)
        cache.put(key, snapshot)
        assertThat((cache.read(key) as ProductListCacheRead.Hit).snapshot).isEqualTo(snapshot)
        val empty = ProductListSnapshot(emptyList(), 3, 2, 0, 0)
        cache.put(key, empty)
        assertThat((cache.read(key) as ProductListCacheRead.Hit).snapshot).isEqualTo(empty)
        assertThat(result.content).hasSize(1)
    }

    @Test
    fun `DB only miss and hit keep all sorts ties filters pages and exact count`() {
        val brand = brands.create("첫").id
        val other = brands.create("둘").id
        val first = products.create(brand, "A", 200, 5).id
        val second = products.create(brand, "B", 100, 5).id
        val third = products.create(brand, "C", 100, 5).id
        products.create(other, "D", 0, 1)
        val buyer = buyer(0)
        likes.like(buyer, first)
        likes.like(buyer, second)
        val variants = listOf("latest", "price_asc", "likes_desc").flatMap { sort ->
            listOf("", "&brandId=$brand").flatMap { filter -> (0..2).map { page -> "?sort=$sort$filter&page=$page&size=2" } }
        }
        variants.forEach { query ->
            doReturn(ProductListCacheRead.Unavailable).whenever(cache).read(any())
            val db = browse(query)
            doCallRealMethod().whenever(cache).read(any())
            val miss = browse(query)
            val hit = browse(query)
            assertThat(miss).isEqualTo(db)
            assertThat(hit).isEqualTo(db)
        }
        assertThat(browse("?sort=price_asc&brandId=$brand")["content"].map { it["id"].asLong() }).containsExactly(third, second, first)
        assertThat(browse("?sort=likes_desc&brandId=$brand")["content"].map { it["id"].asLong() }).containsExactly(second, first, third)
    }

    @Test
    fun `all nine writes evict before returning while excluded writes leave keys`() {
        val buyer = buyer(10000)
        val brand = brands.create("브랜드").id
        val product = products.create(brand, "상품", 100, 5).id
        val order = draft(buyer, product)
        val actions = listOf<() -> Unit>(
            { products.create(brand, "추가", 200, 2) },
            { products.update(product, "변경", 150) },
            { products.adjustStock(product, 7) },
            { brands.update(brand, "이름") },
            { likes.like(buyer, product) },
            { likes.unlike(buyer, product) },
            { orders.confirm(buyer, order) },
            { products.delete(product) },
            { brands.delete(brand) },
        )
        clearInvocations(cache)
        actions.forEach { action ->
            browse()
            assertThat(redis.keys("products:v1:list:* ".trim())).isNotEmpty()
            action()
            assertThat(redis.keys("products:v1:list:*")).isEmpty()
            val miss = browse()
            doReturn(ProductListCacheRead.Unavailable).whenever(cache).read(any())
            assertThat(browse()).isEqualTo(miss)
            doCallRealMethod().whenever(cache).read(any())
        }
        verify(cache, times(9)).evictAll()
        val remaining = product(200, 3)
        browse()
        clearInvocations(cache)
        brands.create("새 브랜드")
        draft(buyer, remaining)
        points.charge(buyer, 1)
        verify(cache, never()).evictAll()
        assertThat(redis.keys("products:v1:list:*")).isNotEmpty()
    }

    @Test
    fun `retained deleted product cache triggers full page and count reload`() {
        val brand = brands.create("브랜드").id
        val ids = (1..3).map { products.create(brand, "상품$it", it * 100L, 2).id }
        val query = "?sort=price_asc&size=2"
        browse(query)
        doNothing().whenever(cache).evictAll()
        products.delete(ids.first())
        CapturedSql.statements.clear()
        val response = browse(query)
        assertThat(response["content"].map { it["id"].asLong() }).containsExactly(ids[1], ids[2])
        assertThat(response["totalElements"].asLong()).isEqualTo(2)
        assertThat(CapturedSql.statements.filter { it.startsWith("select ") }).hasSize(4)
    }

    @Test
    fun `retained bulk deleted brand cache returns other brand and accurate count`() {
        val deletedBrand = brands.create("삭제").id
        val deleted = products.create(deletedBrand, "삭제", 0, 3).id
        val retained = product(100, 3)
        browse("?sort=price_asc&size=1")
        doNothing().whenever(cache).evictAll()
        brands.delete(deletedBrand)
        val response = browse("?sort=price_asc&size=1")
        assertThat(response["content"].map { it["id"].asLong() }).containsExactly(retained).doesNotContain(deleted)
        assertThat(response["totalElements"].asLong()).isEqualTo(1)
    }

    @Test
    fun `corrupt read falls back without put and database failures remain failures`() {
        product(100, 3)
        browse()
        val key = redis.keys("products:v1:list:*")!!.single()
        redis.opsForValue().set(key, "{broken-json", Duration.ofSeconds(30))
        clearInvocations(cache)
        assertThat(browse()["content"].size()).isEqualTo(1)
        verify(cache, never()).put(any(), any())
        assertThat(redis.opsForValue().get(key)).isEqualTo("{broken-json")
        doThrow(IllegalStateException("DB failure")).whenever(queries).findPage(anyOrNull(), any(), any(), any())
        mvc.perform(get("/api/v1/products?brandId=5")).andExpect(status().isInternalServerError)
    }

    @Test
    fun `afterCommit failure does not fail committed business or retry it`() {
        val id = product(100, 3)
        browse()
        clearInvocations(cache)
        observer.events.clear()
        doThrow(IllegalStateException("Redis unavailable")).whenever(cache).evictAll()
        assertThat(products.update(id, "확정", 200).price).isEqualTo(200)
        tx {
            em.flush()
            em.clear()
            assertThat(number("select price from products where id=?", id)).isEqualTo(200)
        }
        verify(cache, times(1)).evictAll()
        assertThat(observer.events.filter { it.kind == "attempt" }).hasSize(1).allMatch { it.outcome == "SUCCESS" }
    }

    @Test
    fun `rollback after real SQL and callback registration does not evict`() {
        val id = product(100, 3)
        browse()
        clearInvocations(cache)
        doAnswer { invocation ->
            invocation.callRealMethod()
            throw IllegalStateException("after registration")
        }.whenever(invalidation).afterCommit()
        assertThrows<IllegalStateException> { products.adjustStock(id, 7) }
        tx {
            em.flush()
            em.clear()
            assertThat(stock(id)).isEqualTo(3)
        }
        verify(cache, never()).evictAll()
        assertThat(redis.keys("products:v1:list:*")).isNotEmpty()
    }

    @Test
    fun `rolled back deadlock attempt never evicts and final confirmation commits once`() {
        val buyer = buyer(1000)
        val id = product(100, 3)
        val order = draft(buyer, id)
        browse()
        clearInvocations(cache)
        observer.events.clear()
        var calls = 0
        doAnswer { invocation ->
            invocation.callRealMethod()
            if (++calls == 1) throw CannotAcquireLockException("injected after SQL", SQLException("deadlock", "40001", 1213))
            Unit
        }.whenever(invalidation).afterCommit()
        orders.confirm(buyer, order)
        tx {
            em.flush()
            em.clear()
            assertThat(stock(id)).isEqualTo(2)
            assertThat(balance(buyer)).isEqualTo(900)
        }
        assertThat(observer.events.filter { it.kind == "attempt" }.map { it.outcome }).containsExactly("DEADLOCK", "SUCCESS")
        verify(cache, times(1)).evictAll()
        assertThat(redis.keys("products:v1:list:*")).isEmpty()
    }
}
