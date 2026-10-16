package com.loopers.performance

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.loopers.domain.product.ProductListCache
import com.loopers.domain.product.ProductListCacheRead
import io.micrometer.core.instrument.MeterRegistry
import org.mockito.kotlin.any
import org.mockito.kotlin.doCallRealMethod
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.whenever
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.redis.core.RedisTemplate
import com.loopers.application.transaction.CapturedSql
import com.loopers.application.transaction.SqlObservationConfiguration
import com.loopers.utils.DatabaseCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.ceil

/** Opt-in, isolated Testcontainers database; never point this tool at a persistent database. */
@Tag("performance")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = ["spring.jpa.show-sql=false"])
@Import(SqlObservationConfiguration::class)
class ProductListBenchmarkTest @Autowired constructor(
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
    private val cleanup: DatabaseCleanUp,
    private val meters: MeterRegistry,
    @Qualifier("redisTemplateMaster") private val redis: RedisTemplate<String, String>,
) {
    @LocalServerPort private var port: Int = 0

    @MockitoSpyBean private lateinit var cache: ProductListCache

    @Test
    fun measure() {
        val phase = System.getProperty("week4.phase", "final")
        val output = Path.of(System.getProperty("week4.output"))
        Files.createDirectories(output)
        try {
            seed()
            val modes = if (phase == "final") listOf("db-only", "miss", "hit") else listOf("db-only")
            for (mode in modes) {
                if (mode == "db-only") {
                    doReturn(ProductListCacheRead.Unavailable).whenever(cache).read(any())
                } else {
                    doCallRealMethod().whenever(cache).read(any())
                }
                cache.evictAll()
                val preparation = mutableListOf<Double>()
                fun prepare() {
                    val started = System.nanoTime()
                    if (mode == "miss") cache.evictAll()
                    preparation.add((System.nanoTime() - started) / 1_000_000.0)
                }
                val brandId = jdbc.queryForObject("select id from brands where name='W4-brand-07'", Long::class.java)!!
                val url = "http://127.0.0.1:$port/api/v1/products?brandId=$brandId&sort=price_asc&page=0&size=20"
                val client = HttpClient.newHttpClient()
                val request = HttpRequest.newBuilder(URI(url)).GET().build()
                fun call(): JsonNode {
                    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                    assertThat(response.statusCode()).isEqualTo(200)
                    return mapper.readTree(response.body())["data"]
                }
                repeat(20) {
                    prepare()
                    call()
                }
                prepare()
                CapturedSql.statements.clear()
                val response = call()
                val sql = CapturedSql.statements.toList()
                val expected = jdbc.queryForList(
                    "select id,price,like_count from products where deleted_at is null and brand_id=? order by price asc,id desc limit 20",
                    brandId,
                )
                assertThat(response["content"].map { it["id"].asLong() }).isEqualTo(expected.map { (it["id"] as Number).toLong() })
                assertThat(response["content"].map { it["price"].asLong() }).isEqualTo(expected.map { (it["price"] as Number).toLong() })
                assertThat(response["content"].map { it["likeCount"].asLong() }).isEqualTo(expected.map { (it["like_count"] as Number).toLong() })
                assertThat(response["totalElements"].asLong()).isEqualTo(1000)
                fun outcome(name: String) = meters.counter("commerce.product.list.cache", "outcome", name).count()
                val beforeHit = outcome("hit")
                val beforeMiss = outcome("miss")
                val beforeLoads = meters.counter("commerce.product.list.db.loads").count()
                val sqlCounts = mutableListOf<Int>()
                preparation.clear()
                val timings = (1..100).map {
                    prepare()
                    CapturedSql.statements.clear()
                    val start = System.nanoTime()
                    val actual = call()
                    assertThat(actual).isEqualTo(response)
                    val elapsed = (System.nanoTime() - start) / 1_000_000.0
                    sqlCounts.add(CapturedSql.statements.size)
                    elapsed
                }
                val expectedSql = if (mode == "hit") 1 else 3
                assertThat(sqlCounts).allMatch { it == expectedSql }
                if (mode == "hit") assertThat(outcome("hit") - beforeHit).isEqualTo(100.0)
                if (mode == "miss") assertThat(outcome("miss") - beforeMiss).isEqualTo(100.0)
                val ids = response["content"].map { it["id"].asLong() }
                val plans = sql.filter { it.startsWith("select ") }.map { statement ->
                    val args: List<Long> = when {
                        statement.contains("order by") -> listOf(brandId, 20L)
                        statement.contains("from brands") -> listOf(brandId)
                        statement.contains("count(") -> listOf(brandId)
                        else -> ids
                    }
                    var index = 0
                    val bound = Regex("\\?").replace(statement) { args[index++].toString() }
                    mapOf(
                        "sql" to bound,
                        "explain" to jdbc.queryForList("explain $bound"),
                        "analyze" to jdbc.queryForList("explain analyze $bound"),
                    )
                }
                val sorted = timings.sorted()
                fun percentile(p: Double) = sorted[ceil(p * sorted.size).toInt() - 1]
                val result = mapOf(
                    "phase" to phase, "mode" to mode, "database" to jdbc.queryForObject("select version()", String::class.java),
                    "products" to 10000, "likes" to 45000, "brandOrdinal" to 7, "brandId" to brandId,
                    "url" to url, "warmup" to 20, "requests" to 100, "concurrency" to 1,
                    "sqlPerRequest" to sql.size, "observedSqlCounts" to sqlCounts,
                    "observedHits" to outcome("hit") - beforeHit, "observedMisses" to outcome("miss") - beforeMiss,
                    "dbListLoads" to meters.counter("commerce.product.list.db.loads").count() - beforeLoads,
                    "preparationMs" to preparation, "sql" to sql, "plans" to plans,
                    "p50Ms" to percentile(0.50), "p95Ms" to percentile(0.95), "p99Ms" to percentile(0.99),
                    "latenciesMs" to timings, "response" to response,
                )
                mapper.writerWithDefaultPrettyPrinter().writeValue(output.resolve(if (phase == "final") "final-$mode.json" else "$phase.json").toFile(), result)
                println("WEEK4_BENCHMARK mode=$mode phase=$phase brandId=$brandId sql=${sql.size} p50=${percentile(0.50)} p95=${percentile(0.95)} p99=${percentile(0.99)}")
            }
            measureEviction(output)
        } finally {
            doCallRealMethod().whenever(cache).read(any())
            cache.evictAll()
            cleanup.truncateAllTables()
        }
    }

    private fun measureEviction(output: Path) {
        cache.evictAll()
        val prefix = "products:v1:list:"
        // Include unrelated keys: a shared-namespace SCAN also pays for these keys.
        val unrelated = (0 until 1000).map { "week4:unrelated:$it" }
        try {
            redis.opsForValue().multiSet((0 until 501).associate { "${prefix}benchmark:$it" to "snapshot" })
            redis.opsForValue().multiSet(unrelated.associateWith { "preserve" })
            fun commands(name: String): Long = redis.connectionFactory!!.connection.use { connection ->
                val info = connection.serverCommands().info("commandstats")!!
                Regex("calls=([0-9]+)").find(info.getProperty("cmdstat_$name", "calls=0"))!!.groupValues[1].toLong()
            }
            val scans = commands("scan")
            val deletes = commands("del")
            val evicted = meters.counter("commerce.product.list.cache.evicted.keys").count()
            val start = System.nanoTime()
            cache.evictAll()
            val elapsed = (System.nanoTime() - start) / 1_000_000.0
            val result = mapOf(
                "namespaceKeys" to 501,
                "unrelatedKeys" to unrelated.size,
                "scanCommands" to commands("scan") - scans,
                "deleteCommands" to commands("del") - deletes,
                "deletedKeys" to meters.counter("commerce.product.list.cache.evicted.keys").count() - evicted,
                "elapsedMs" to elapsed,
            )
            assertThat(redis.keys("$prefix*")).isEmpty()
            assertThat(redis.opsForValue().multiGet(unrelated)).allMatch { it == "preserve" }
            mapper.writerWithDefaultPrettyPrinter().writeValue(output.resolve("eviction.json").toFile(), result)
        } finally {
            redis.delete(unrelated)
            cache.evictAll()
        }
    }

    private fun seed() {
        cleanup.truncateAllTables()
        jdbc.batchUpdate(
            "insert into brands(id,name,created_at,updated_at) values (?,?, '2026-01-01', '2026-01-01')",
            (1..10).map { arrayOf<Any>(1000L + it, "W4-brand-%02d".format(it)) },
        )
        jdbc.batchUpdate(
            "insert into products(id,brand_id,name,price,stock,like_count,created_at,updated_at) values (?,?,?,?,50,?,'2026-01-01','2026-01-01')",
            (0 until 10000).map { i -> arrayOf<Any>(10001L + i, 1001L + i % 10, "W4-product-$i", (i * 7919L % 1000) * 100, i % 10L) },
        )
        jdbc.batchUpdate(
            "insert into product_likes(user_id,product_id,liked_at) values (?,?,'2026-01-01')",
            (0 until 10000).flatMap { i -> (1..(i % 10)).map { user -> arrayOf<Any>(user.toLong(), 10001L + i) } },
        )
        assertThat(
            checkNotNull(
                jdbc.queryForObject(
            "select count(*) from products p where p.like_count <> (select count(*) from product_likes l where l.product_id=p.id)",
            Long::class.java,
        ),
            ),
        ).isZero()
        jdbc.execute("analyze table products,brands,product_likes")
    }
}
