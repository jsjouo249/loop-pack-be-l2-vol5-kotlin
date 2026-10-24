package com.loopers.payment

import com.loopers.application.transaction.CommerceTransactionTestSupport
import com.loopers.domain.order.OrderRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import java.nio.file.Files
import java.nio.file.Path

class PointPaymentMigrationTest : CommerceTransactionTestSupport() {
    @Autowired private lateinit var repository: OrderRepository
    private fun script(name: String): String = Files.readString(
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .map { it.resolve("docs/week5/sql/$name") }.first { Files.exists(it) },
    )
    private fun runScript(sql: String) = sql.lines().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
        .split(';').filter { it.isNotBlank() }.forEach { jdbc.execute(it) }

    @Test
    fun `legacy schema upgrade preserves point facts and replay rejects with original conflict`() {
        val user = buyer(10_000)
        val product = product(1000, 5)
        val order = draft(user, product)
        tx {
            val legacy = repository.findForUpdate(order)!!
            legacy.confirm()
            repository.saveConfirmation(legacy)
            jdbc.update("update products set stock=4 where id=?", product)
            jdbc.update("update point_balances set balance=9000 where user_id=?", user)
        }
        val before = jdbc.queryForList("select id,user_id,total_amount,status,payment_amount,payment_result,confirmed_at,created_at,updated_at from orders")
        val lines = jdbc.queryForList("select * from order_items")
        listOf("payment_issues", "stock_reservations", "payment_attempts").forEach { jdbc.execute("drop table $it") }
        jdbc.execute("alter table orders drop column payment_method")
        runScript(script("upgrade-payment-boundary.sql"))
        repeat(2) { runScript(script("backfill-point-method.sql")) }
        assertThat(jdbc.queryForList("select id,user_id,total_amount,status,payment_amount,payment_result,confirmed_at,created_at,updated_at from orders")).isEqualTo(before)
        assertThat(jdbc.queryForList("select * from order_items")).isEqualTo(lines)
        assertThat(orders.getForUser(user, order).paymentMethod!!.name).isEqualTo("POINT")
        assertThat(balance(user)).isEqualTo(9000)
        assertThat(stock(product)).isEqualTo(4)
        mvc.perform(post("/api/v1/orders/$order/confirm").header("X-USER-ID", user).contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isConflict).andExpect(jsonPath("$.meta.errorCode").value("ORDER_ALREADY_CONFIRMED"))
    }
}
