package com.loopers.application.cache

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.loopers.application.product.CustomerProductQueryService
import com.loopers.application.product.ProductApplicationService
import com.loopers.application.product.ProductListInvalidation
import com.loopers.application.transaction.CommerceTransactionTestSupport
import com.loopers.application.transaction.RetryingWriteExecutor
import com.loopers.config.redis.RedisProperties
import com.loopers.domain.brand.BrandRepository
import com.loopers.domain.like.ProductLikeRepository
import com.loopers.domain.product.ProductListCache
import com.loopers.domain.product.ProductListCacheRead
import com.loopers.domain.product.ProductListQuery
import com.loopers.domain.product.ProductRepository
import com.loopers.infrastructure.product.RedisProductListCache
import io.lettuce.core.ReadFrom
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doCallRealMethod
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.redis.connection.RedisStaticMasterReplicaConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.serializer.StringRedisSerializer
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ProductListCacheRaceTest : CommerceTransactionTestSupport() {
    @MockitoSpyBean private lateinit var cache: ProductListCache

    @MockitoSpyBean private lateinit var queries: ProductListQuery

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var meters: MeterRegistry

    @Autowired private lateinit var settings: RedisProperties

    @Autowired private lateinit var productRepository: ProductRepository

    @Autowired private lateinit var brandRepository: BrandRepository

    @Autowired private lateinit var likeRepository: ProductLikeRepository

    @Autowired private lateinit var executor: RetryingWriteExecutor

    @Autowired
    @Qualifier("redisTemplateMaster")
    private lateinit var redis: RedisTemplate<String, String>

    private fun browse(): JsonNode {
        val response = mvc.perform(get("/api/v1/products?sort=price_asc")).andExpect(status().isOk).andReturn().response
        return mapper.readTree(response.contentAsString)["data"]
    }

    @Test
    fun `commit followed by failed eviction allows stale display then expires and refills`() {
        val id = product(100, 3)
        val old = browse()
        doThrow(IllegalStateException("delete failed")).whenever(cache).evictAll()
        products.update(id, "현재", 200)
        assertThat(browse()).isEqualTo(old)
        assertPrivateAndOrderRules(id)
        doCallRealMethod().whenever(cache).evictAll()
        expireThenCompareCurrent()
        assertDeletionGuard(id)
    }

    @Test
    fun `old reader fills after successful commit eviction and worker finishes before recovery`() {
        val id = product(100, 3)
        val read = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        doAnswer { invocation ->
            read.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            invocation.callRealMethod()
        }.whenever(cache).put(any(), any())
        try {
            val oldReader = pool.submit<JsonNode> { browse() }
            check(read.await(10, TimeUnit.SECONDS))
            products.update(id, "현재", 200)
            assertThat(redis.keys("products:v1:list:*")).isEmpty()
            release.countDown()
            val old = oldReader.get(30, TimeUnit.SECONDS)
            assertThat(old["content"][0]["price"].asLong()).isEqualTo(100)
            assertThat(browse()).isEqualTo(old)
        } finally {
            release.countDown()
            pool.shutdownNow()
            check(pool.awaitTermination(10, TimeUnit.SECONDS))
        }
        doCallRealMethod().whenever(cache).put(any(), any())
        // Keep the stale snapshot while checking private/order rules; then restore eviction for recovery.
        doNothing().whenever(cache).evictAll()
        assertPrivateAndOrderRules(id)
        doCallRealMethod().whenever(cache).evictAll()
        expireThenCompareCurrent()
        assertDeletionGuard(id)
    }

    @Test
    fun `late fill after product and brand deletion never exposes removed products to new requests`() {
        listOf(false, true).forEach { deleteBrand ->
            cache.evictAll()
            val brand = brands.create("삭제$deleteBrand").id
            val id = products.create(brand, "삭제", 100, 3).id
            val read = CountDownLatch(1)
            val release = CountDownLatch(1)
            val pool = Executors.newSingleThreadExecutor()
            doAnswer { invocation ->
                read.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                invocation.callRealMethod()
            }.whenever(cache).put(any(), any())
            try {
                val oldReader = pool.submit<JsonNode> { browse() }
                check(read.await(10, TimeUnit.SECONDS))
                if (deleteBrand) brands.delete(brand) else products.delete(id)
                release.countDown()
                assertThat(oldReader.get(30, TimeUnit.SECONDS)["content"].map { it["id"].asLong() }).contains(id)
            } finally {
                release.countDown()
                pool.shutdownNow()
                check(pool.awaitTermination(10, TimeUnit.SECONDS))
            }
            doCallRealMethod().whenever(cache).put(any(), any())
            assertThat(browse()["content"].map { it["id"].asLong() }).doesNotContain(id)
            assertThat(browse()["totalElements"].asLong()).isZero()
        }
    }

    @Test
    fun `hit visibility and fallback count share the same repeatable read snapshot`() {
        val brand = brands.create("브랜드").id
        val first = products.create(brand, "첫", 100, 3).id
        val second = products.create(brand, "둘", 200, 3).id
        browse()
        doNothing().whenever(cache).evictAll()
        products.delete(first)
        doAnswer { invocation ->
            val visible = invocation.callRealMethod()
            val pool = Executors.newSingleThreadExecutor()
            try {
                pool.submit { products.delete(second) }.get(30, TimeUnit.SECONDS)
            } finally {
                pool.shutdownNow()
                check(pool.awaitTermination(10, TimeUnit.SECONDS))
            }
            visible
        }.whenever(queries).findVisibleIds(any())
        val response = browse()
        assertThat(response["content"].map { it["id"].asLong() }).containsExactly(second)
        assertThat(response["totalElements"].asLong()).isEqualTo(1)
        assertThat(number("select count(*) from products where deleted_at is null")).isZero()
        doCallRealMethod().whenever(queries).findVisibleIds(any())
        assertThat(browse()["content"].size()).isZero()
    }

    @Test
    fun `independent clients and service objects share namespace invalidation in both directions`() {
        val id = product(100, 3)
        val configuration = RedisStaticMasterReplicaConfiguration(settings.master.host, settings.master.port)
        val factory = LettuceConnectionFactory(configuration, LettuceClientConfiguration.builder().readFrom(ReadFrom.MASTER).build())
        factory.afterPropertiesSet()
        factory.start()
        try {
            val template = RedisTemplate<String, String>().apply {
                connectionFactory = factory
                keySerializer = StringRedisSerializer()
                valueSerializer = StringRedisSerializer()
                afterPropertiesSet()
            }
            val independentCache = RedisProductListCache(template, mapper, meters, Duration.ofSeconds(30))
            val independentWrites = ProductApplicationService(productRepository, brandRepository, executor, ProductListInvalidation(independentCache))
            val independentReads = CustomerProductQueryService(productRepository, brandRepository, likeRepository, queries, independentCache, meters)
            val tx = TransactionTemplate(manager).apply {
                isReadOnly = true
                isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
            }
            browse()
            independentWrites.update(id, "다른 인스턴스", 200)
            assertThat(browse()["content"][0]["price"].asLong()).isEqualTo(200)
            assertThat(tx.execute { independentReads.list(null, 0, 20, "price_asc") }!!.content.single().price).isEqualTo(200)
            products.update(id, "기본 인스턴스", 300)
            assertThat(tx.execute { independentReads.list(null, 0, 20, "price_asc") }!!.content.single().price).isEqualTo(300)
            assertThat(browse()["content"][0]["price"].asLong()).isEqualTo(300)
        } finally {
            factory.destroy()
        }
    }

    private fun assertPrivateAndOrderRules(id: Long) {
        val buyer = buyer(1000)
        val stranger = buyer(0)
        val order = draft(buyer, id)
        assertThat(orders.getForUser(buyer, order).items.single().unitPrice).isEqualTo(200)
        mvc.perform(get("/api/v1/orders/$order").header("X-USER-ID", stranger)).andExpect(status().isForbidden)
        mvc.perform(get("/api/v1/users/$buyer/likes").header("X-USER-ID", stranger)).andExpect(status().isForbidden)
        val otherBalance = mvc.perform(get("/api/v1/points").header("X-USER-ID", stranger)).andExpect(status().isOk).andReturn().response
        assertThat(mapper.readTree(otherBalance.contentAsString)["data"]["userId"].asLong()).isEqualTo(stranger)
        assertThat(mapper.readTree(otherBalance.contentAsString)["data"]["balance"].asLong()).isZero()
        products.update(id, "더 최신", 300)
        orders.confirm(buyer, order)
        assertThat(orders.getForUser(buyer, order).paymentAmount).isEqualTo(200)
        assertThat(balance(buyer)).isEqualTo(800)
        assertThat(stock(id)).isEqualTo(2)
        val rejected = draft(buyer, id)
        products.adjustStock(id, 0)
        org.junit.jupiter.api.assertThrows<com.loopers.domain.product.InsufficientStockException> { orders.confirm(buyer, rejected) }
        assertThat(balance(buyer)).isEqualTo(800)
        assertThat(stock(id)).isZero()
    }

    private fun assertDeletionGuard(id: Long) {
        val buyer = buyer(1000)
        val order = draft(buyer, id)
        doThrow(IllegalStateException("deletion invalidation failed")).whenever(cache).evictAll()
        products.delete(id)
        assertThat(browse()["content"].size()).isZero()
        val failure = org.junit.jupiter.api.assertThrows<com.loopers.domain.commerce.CommerceException> { orders.confirm(buyer, order) }
        assertThat(failure.reason).isEqualTo(com.loopers.domain.commerce.CommerceFailure.PRODUCT_NOT_FOUND)
        assertThat(balance(buyer)).isEqualTo(1000)
    }

    private fun expireThenCompareCurrent() {
        val key = "products:v1:list:brand=all:sort=price_asc:page=0:size=20"
        assertThat(redis.expire(key, Duration.ofMillis(250)) == true).isTrue()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (redis.hasKey(key) == true && System.nanoTime() < deadline) Thread.sleep(10)
        assertThat(redis.hasKey(key)).isFalse()
        doAnswer { ProductListCacheRead.Unavailable }.whenever(cache).read(any())
        val current = browse()
        doCallRealMethod().whenever(cache).read(any())
        assertThat(browse()).isEqualTo(current)
        assertThat(browse()).isEqualTo(current)
        assertThat(current["content"][0]["price"].asLong()).isEqualTo(300)
        assertThat(current["content"][0]["stock"].asInt()).isZero()
    }
}
