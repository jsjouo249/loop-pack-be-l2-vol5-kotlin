package com.loopers.infrastructure.product

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.loopers.config.redis.RedisConfig
import com.loopers.domain.product.ProductListCache
import com.loopers.domain.product.ProductListCacheRead
import com.loopers.domain.product.ProductListKey
import com.loopers.domain.product.ProductListSnapshot
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.RedisCallback
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.ScanOptions
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.Locale
import java.util.concurrent.TimeUnit

@Component
class RedisProductListCache(
    @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) private val redis: RedisTemplate<String, String>,
    mapper: ObjectMapper,
    private val meters: MeterRegistry,
    @Value("\${commerce.product-list-cache.ttl:PT30S}") private val ttl: Duration,
) : ProductListCache {
    companion object {
        const val PREFIX = "products:v1:list:"
        private const val BATCH_SIZE = 200
    }

    private val log = LoggerFactory.getLogger(javaClass)
    private val codec = mapper.copy().disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
        .enable(SerializationFeature.WRITE_DATES_WITH_ZONE_ID)

    init {
        require(!ttl.isNegative && !ttl.isZero)
    }

    private fun key(key: ProductListKey): String = PREFIX +
        "brand=${key.brandId ?: "all"}:sort=${key.sort.name.lowercase(Locale.ROOT)}:page=${key.page}:size=${key.size}"

    override fun read(key: ProductListKey): ProductListCacheRead = try {
        val value = redis.opsForValue().get(key(key))
        if (value == null) {
            event("miss")
            ProductListCacheRead.Miss
        } else {
            val snapshot = codec.readValue(value, ProductListSnapshot::class.java)
            event("hit")
            ProductListCacheRead.Hit(snapshot)
        }
    } catch (failure: Exception) {
        failed("read", failure)
        ProductListCacheRead.Unavailable
    }

    override fun put(key: ProductListKey, snapshot: ProductListSnapshot) {
        try {
            redis.opsForValue().set(key(key), codec.writeValueAsString(snapshot), ttl)
            event("put")
        } catch (failure: Exception) {
            failed("put", failure)
        }
    }

    override fun evictAll() {
        val started = System.nanoTime()
        var keys = 0L
        var deletes = 0L
        try {
            // One shared namespace, bounded DEL arguments; never FLUSH the shared database.
            redis.execute(
                RedisCallback { connection ->
                    connection.keyCommands().scan(
                        ScanOptions.scanOptions().match("$PREFIX*").count(BATCH_SIZE.toLong()).build(),
                    ).use { cursor ->
                        val batch = ArrayList<ByteArray>(BATCH_SIZE)
                        while (cursor.hasNext()) {
                            batch.add(cursor.next())
                            if (batch.size == BATCH_SIZE) {
                                keys += checkNotNull(connection.keyCommands().del(*batch.toTypedArray()))
                                deletes++
                                batch.clear()
                            }
                        }
                        if (batch.isNotEmpty()) {
                            keys += checkNotNull(connection.keyCommands().del(*batch.toTypedArray()))
                            deletes++
                        }
                    }
                    null
                },
            )
            event("evict")
        } catch (failure: Exception) {
            failed("evict", failure)
        } finally {
            meters.counter("commerce.product.list.cache.evicted.keys").increment(keys.toDouble())
            meters.counter("commerce.product.list.cache.delete.commands").increment(deletes.toDouble())
            meters.timer("commerce.product.list.cache.eviction").record(System.nanoTime() - started, TimeUnit.NANOSECONDS)
            log.info(
                "product_list_cache eviction keys={} deleteCommands={} elapsedMicros={}",
                keys,
                deletes,
                (System.nanoTime() - started) / 1000,
            )
        }
    }

    private fun event(outcome: String) {
        meters.counter("commerce.product.list.cache", "outcome", outcome).increment()
    }

    private fun failed(operation: String, failure: Exception) {
        event("${operation}_failure")
        log.warn("product_list_cache operation={} failed", operation, failure)
    }
}
