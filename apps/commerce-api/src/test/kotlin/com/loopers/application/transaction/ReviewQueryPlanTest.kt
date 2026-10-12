package com.loopers.application.transaction

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class ReviewQueryPlanTest : CommerceTransactionTestSupport() {
    @Test
    fun `latest price and popularity pages use their ordering indexes`() {
        val brandIds = (1..10).map { brands.create("브랜드$it").id }
        jdbc.batchUpdate(
            "insert into products(brand_id,name,price,stock,like_count,created_at,updated_at) values (?,?,?,5,?,now(6),now(6))",
            (0 until 2000).map { index -> arrayOf<Any>(brandIds[index % 10], "상품$index", index * 100L, index % 10L) },
        )
        // 대량 fixture도 relation을 source of truth로 갖춘다.
        val productIds = jdbc.queryForList("select id from products order by id", Long::class.java)
        jdbc.batchUpdate(
            "insert into product_likes(user_id,product_id,liked_at) values (?,?,now(6))",
            productIds.flatMapIndexed { index, id -> (1..(index % 10)).map { user -> arrayOf<Any>(user.toLong(), id) } },
        )
        jdbc.execute("analyze table products")
        val sorts = listOf(
            "created_at desc, id desc" to "latest",
            "price asc, id desc" to "price",
            "like_count desc, id desc" to "likes",
        )
        sorts.forEach { (ordering, suffix) ->
            listOf(false, true).forEach { filterBrand ->
                val condition = if (filterBrand) "and brand_id=${brandIds[3]}" else ""
                val plan = jdbc.queryForObject(
                    "explain analyze select id from products where deleted_at is null $condition order by $ordering limit 20",
                    String::class.java,
                )!!
                val expected = "idx_products_active_${if (filterBrand) "brand_" else ""}$suffix"
                assertThat(plan).contains(expected).doesNotContain("Sort:", "product_likes", "Aggregate:")
                println("REVIEW_QUERY_PLAN index=$expected $plan")
            }
        }
    }

    @Test
    fun `legacy projection migration preserves rows and backfill is repeatable`() {
        val scriptPath = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .map { it.resolve("docs/week3/sql/product-like-projection.sql") }.first(Files::exists)
        val script = Files.readString(scriptPath).lineSequence().filterNot { it.trimStart().startsWith("--") }
            .joinToString("\n").replace(Regex("\\bproduct_likes\\b"), "review_projection_likes")
            .replace(Regex("\\bproducts\\b"), "review_projection_products")
        jdbc.execute(
            "create table review_projection_products (id bigint primary key, brand_id bigint, name varchar(255), " +
                "price bigint, stock int, created_at datetime(6), updated_at datetime(6), deleted_at datetime(6))",
        )
        try {
            jdbc.execute("create table review_projection_likes (user_id bigint, product_id bigint)")
            jdbc.update(
                "insert into review_projection_products values " +
                    "(1,1,'active',100,5,now(6),now(6),null), " +
                    "(2,1,'deleted',200,7,now(6),now(6),now(6)), (3,1,'zero',0,0,now(6),now(6),null)",
            )
            jdbc.update("insert into review_projection_likes values (1,1),(2,1),(1,2)")
            val before = jdbc.queryForList("select id,price,stock,updated_at,deleted_at from review_projection_products order by id")
            val statements = script.split(';').map(String::trim).filter(String::isNotEmpty)
            statements.filter { it.startsWith("ALTER") || it.startsWith("CREATE") }.forEach(jdbc::execute)
            val update = statements.single { it.startsWith("UPDATE") }
            val verification = statements.single { it.startsWith("SELECT") }
            repeat(2) {
                tx {
                    jdbc.update(update)
                    assertThat(checkNotNull(jdbc.queryForObject(verification, Long::class.java))).isZero()
                }
            }
            assertThat(jdbc.queryForList("select like_count from review_projection_products order by id", Long::class.java))
                .containsExactly(2L, 1L, 0L)
            assertThat(jdbc.queryForList("select id,price,stock,updated_at,deleted_at from review_projection_products order by id")).isEqualTo(before)
            assertThat(number("select count(*) from review_projection_likes")).isEqualTo(3)
        } finally {
            jdbc.execute("drop table if exists review_projection_likes")
            jdbc.execute("drop table review_projection_products")
        }
    }
}
