package com.loopers.application.transaction

import com.loopers.confirmWithTestCard
import org.assertj.core.api.Assertions.assertThat
import org.hibernate.resource.jdbc.spi.StatementInspector
import org.junit.jupiter.api.Test
import org.mockito.kotlin.whenever
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import java.util.concurrent.CopyOnWriteArrayList
import com.loopers.application.like.ProductLikeApplicationService
import org.springframework.beans.factory.annotation.Autowired

class SqlContractTest : CommerceTransactionTestSupport() {
    @Autowired private lateinit var likes: ProductLikeApplicationService

    @Test
    fun `simple assignments use partial active updates without prior select for update`() {
        val brand = brands.create("브랜드").id
        val product = products.create(brand, "상품", 1000, 5).id
        val cases = listOf<Pair<() -> Unit, Set<String>>>(
            {
                products.update(product, "변경", 2000)
                Unit
            } to setOf("name", "price", "updated_at"),
            {
                products.adjustStock(product, 10)
                Unit
            } to setOf("stock", "updated_at"),
            {
                brands.update(brand, "변경")
                Unit
            } to setOf("name", "updated_at"),
            { products.delete(product) } to setOf("deleted_at", "updated_at"),
        )
        cases.forEach { (action, fields) ->
            CapturedSql.statements.clear()
            action()
            val statements = CapturedSql.statements.toList()
            val updateIndex = statements.indexOfFirst { it.startsWith("update ") }
            assertThat(updateIndex).isGreaterThanOrEqualTo(0)
            assertThat(statements.take(updateIndex)).noneMatch { it.contains("for update") }
            val update = statements[updateIndex]
            assertThat(update).contains("deleted_at is null")
            val set = update.substringAfter(" set ").substringBefore(" where ")
            val actual = Regex("(?:^|, )([a-z_]+) =").findAll(set).map { it.groupValues[1] }.toSet()
            assertThat(actual).isEqualTo(fields)
            println("PARTIAL_SQL $update")
        }
    }

    @Test
    fun `confirmation locks order attempt then products by primary key and only updates confirmation fields`() {
        val buyer = buyer(10_000)
        val brand = brands.create("브랜드").id
        val first = products.create(brand, "첫", 1000, 5).id
        val second = products.create(brand, "둘", 1000, 5).id
        val order = orders.create(
            buyer,
            listOf(
            com.loopers.application.order.OrderItemInput(second, 1),
            com.loopers.application.order.OrderItemInput(first, 1),
        ),
        ).id
        val lockedProducts = CopyOnWriteArrayList<Long>()
        org.mockito.kotlin.doAnswer { invocation ->
            lockedProducts.add(invocation.getArgument(0))
            invocation.callRealMethod()
        }.whenever(productStorage).findActiveForUpdate(org.mockito.kotlin.any())
        CapturedSql.statements.clear()
        orders.confirmWithTestCard(buyer, order)
        assertThat(lockedProducts).containsExactly(first, second)
        val statements = CapturedSql.statements.toList()
        val lockedTables = statements.filter { it.contains("for update") }.map { sql ->
            Regex("from ([a-z_]+)").find(sql)!!.groupValues[1]
        }
        assertThat(lockedTables.first()).isEqualTo("orders")
        assertThat(lockedTables).doesNotContain("point_balances")
        assertThat(lockedTables).doesNotContain("users", "brands")
        val confirmation = statements.last { it.startsWith("update orders ") }
        assertThat(confirmation).doesNotContainPattern("(total_amount|user_id|created_at)\\s*=")
        assertThat(statements).noneMatch { it.startsWith("update order_items ") || it.startsWith("delete from order_items") }
        println("CONFIRMATION_SQL $confirmation")
    }

    @Test
    fun `all ten protected write operations cross the common executor`() {
        val buyer = buyer(0)
        val brand = brands.create("브랜드").id
        observer.events.clear()
        val product = products.create(brand, "상품", 1000, 2).id
        products.update(product, "변경", 1000)
        products.adjustStock(product, 3)
        brands.update(brand, "변경")
        points.charge(buyer, 2000)
        orders.confirmWithTestCard(buyer, draft(buyer, product))
        likes.like(buyer, product)
        likes.unlike(buyer, product)
        products.delete(product)
        brands.delete(brand)
        assertThat(observer.events.filter { it.kind == "request" }.map { it.operation }).containsExactlyInAnyOrder(
            "product.create",
            "product.details",
            "product.stock",
            "brand.rename",
            "point.charge",
            "payment.prepare",
            "payment.apply",
            "product.delete",
            "brand.delete",
            "product.like",
            "product.unlike",
        )
        assertThat(observer.events).allMatch { it.attempt == 1 && it.outcome == "SUCCESS" }
    }
}

object CapturedSql : StatementInspector {
    val statements = CopyOnWriteArrayList<String>()
    override fun inspect(sql: String): String {
        statements.add(sql.lowercase())
        return sql
    }
}

@TestConfiguration
class SqlObservationConfiguration {
    @Bean
    fun sqlObservation() = HibernatePropertiesCustomizer { properties ->
        properties["hibernate.session_factory.statement_inspector"] = CapturedSql
    }
}
