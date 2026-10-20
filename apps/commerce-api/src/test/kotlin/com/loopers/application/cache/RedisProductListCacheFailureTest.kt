package com.loopers.application.cache

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.loopers.domain.product.ProductListCacheRead
import com.loopers.domain.product.ProductListKey
import com.loopers.domain.product.ProductListSnapshot
import com.loopers.domain.product.ProductSort
import com.loopers.infrastructure.product.RedisProductListCache
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.core.RedisCallback
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Duration

class RedisProductListCacheFailureTest {
    private val redis: RedisTemplate<String, String> = mock()
    private val values: ValueOperations<String, String> = mock()
    private val meters = SimpleMeterRegistry()
    private val ttl = Duration.ofSeconds(30)
    private val cache = RedisProductListCache(redis, jacksonObjectMapper().findAndRegisterModules(), meters, ttl)
    private val key = ProductListKey(null, ProductSort.LATEST, 0, 20)
    private val empty = ProductListSnapshot(emptyList(), 0, 20, 0, 0)
    private val failure = RedisConnectionFailureException("test unavailable")

    @Test
    fun `read failure is distinct from miss and separately observed`() {
        whenever(redis.opsForValue()).thenReturn(values)
        whenever(values.get(any())).thenThrow(failure)
        assertThat(cache.read(key)).isEqualTo(ProductListCacheRead.Unavailable)
        assertThat(meters.counter("commerce.product.list.cache", "outcome", "read_failure").count()).isEqualTo(1.0)
        assertThat(meters.counter("commerce.product.list.cache", "outcome", "miss").count()).isZero()
    }

    @Test
    fun `put failure is isolated and separately observed`() {
        whenever(redis.opsForValue()).thenReturn(values)
        doThrow(failure).whenever(values).set(any(), any(), eq(ttl))
        cache.put(key, empty)
        assertThat(meters.counter("commerce.product.list.cache", "outcome", "put_failure").count()).isEqualTo(1.0)
    }

    @Test
    fun `eviction failure is isolated and separately observed`() {
        whenever(redis.execute(any<RedisCallback<Any?>>())).thenThrow(failure)
        cache.evictAll()
        assertThat(meters.counter("commerce.product.list.cache", "outcome", "evict_failure").count()).isEqualTo(1.0)
        assertThat(meters.timer("commerce.product.list.cache.eviction").count()).isEqualTo(1)
    }
}
