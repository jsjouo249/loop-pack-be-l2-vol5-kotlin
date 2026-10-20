package com.loopers.performance

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
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
class ProductListBaselineBenchmarkTest @Autowired constructor(
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
    private val cleanup: DatabaseCleanUp,
) {
    @LocalServerPort private var port: Int = 0

    @Test
    fun measure() {
        val phase = System.getProperty("week4.phase", "baseline")
        val output = Path.of(System.getProperty("week4.output"))
        Files.createDirectories(output)
        try {
            seed()
            val brandId = jdbc.queryForObject("select id from brands where name='W4-brand-07'", Long::class.java)!!
            val url = "http://127.0.0.1:$port/api/v1/products?brandId=$brandId&sort=price_asc&page=0&size=20"
            val client = HttpClient.newHttpClient()
            val request = HttpRequest.newBuilder(URI(url)).GET().build()
            fun call(): JsonNode {
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                assertThat(response.statusCode()).isEqualTo(200)
                return mapper.readTree(response.body())["data"]
            }
            repeat(20) { call() }
            CapturedSql.statements.clear()
            val response = call()
            val sql = CapturedSql.statements.toList()
            val expected = jdbc.queryForList(
                "select id,price,like_count from products where deleted_at is null and brand_id=? order by price asc,id desc limit 20",
                brandId,
            )
            assertThat(response["content"].map { it["id"].asLong() }).isEqualTo(expected.map { (it["id"] as Number).toLong() })
            assertThat(response["totalElements"].asLong()).isEqualTo(1000)
            val sqlCounts = mutableListOf<Int>()
            var dbLoads = 0
            val timings = (1..100).map {
                CapturedSql.statements.clear()
                val start = System.nanoTime()
                assertThat(call()).isEqualTo(response)
                val elapsed = (System.nanoTime() - start) / 1_000_000.0
                sqlCounts.add(CapturedSql.statements.size)
                dbLoads += CapturedSql.statements.count { it.contains("order by") }
                elapsed
            }
            assertThat(sqlCounts).allMatch { it == 5 }
            assertThat(dbLoads).isEqualTo(100)
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
                mapOf("sql" to bound, "explain" to jdbc.queryForList("explain $bound"),
                    "analyze" to jdbc.queryForList("explain analyze $bound"))
            }
            val sorted = timings.sorted()
            fun percentile(p: Double) = sorted[ceil(p * sorted.size).toInt() - 1]
            val result = mapOf(
                "phase" to phase, "database" to jdbc.queryForObject("select version()", String::class.java),
                "products" to 10000, "likes" to 45000, "brandOrdinal" to 7, "brandId" to brandId,
                "url" to url, "warmup" to 20, "requests" to 100, "concurrency" to 1,
                "sqlPerRequest" to sql.size, "observedSqlCounts" to sqlCounts, "dbListLoads" to dbLoads, "sql" to sql, "plans" to plans,
                "p50Ms" to percentile(0.50), "p95Ms" to percentile(0.95), "p99Ms" to percentile(0.99),
                "latenciesMs" to timings, "response" to response,
            )
            mapper.writerWithDefaultPrettyPrinter().writeValue(output.resolve("$phase.json").toFile(), result)
            println("WEEK4_BENCHMARK phase=$phase brandId=$brandId sql=${sql.size} p50=${percentile(0.50)} p95=${percentile(0.95)} p99=${percentile(0.99)}")
        } finally {
            cleanup.truncateAllTables()
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
        assertThat(checkNotNull(jdbc.queryForObject(
            "select count(*) from products p where p.like_count <> (select count(*) from product_likes l where l.product_id=p.id)",
            Long::class.java,
        ))).isZero()
        jdbc.execute("analyze table products,brands,product_likes")
    }
}
