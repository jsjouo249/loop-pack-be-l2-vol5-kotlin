package com.loopers.application.transaction

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.http.MediaType
import java.nio.file.Files
import java.nio.file.Path

class PointInitializationTest : CommerceTransactionTestSupport() {
    @Test
    fun `initialization creates one zero account and zero payment preserves it`() {
        val buyer = buyer(0)
        assertThat(balance(buyer)).isZero()
        val product = product(0, 1)
        orders.confirm(buyer, draft(buyer, product))
        assertThat(balance(buyer)).isZero()
        assertThat(number("select count(*) from point_balances")).isEqualTo(1)
        assertThat(stock(product)).isZero()
    }

    @Test
    fun `backfill is explicit repeatable and preserves every existing balance`() {
        val funded = buyer(9000)
        val missing = buyer(0)
        jdbc.update("delete from point_balances where user_id = ?", missing)
        val path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .map { it.resolve("docs/week3/sql/backfill-point-accounts.sql") }.first { Files.exists(it) }
        val sql = Files.readString(path).lines().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
        repeat(2) {
            tx { jdbc.update(sql) }
            assertThat(balance(funded)).isEqualTo(9000)
            assertThat(balance(missing)).isZero()
            assertThat(number("select count(*) from users u left join point_balances p on p.user_id=u.id where p.user_id is null")).isZero()
            assertThat(number("select count(*) from point_balances")).isEqualTo(2)
        }
    }

    @Test
    fun `missing account is a server integrity error for read charge and confirmation`() {
        val buyer = buyer(0)
        val product = product(0, 1)
        val order = draft(buyer, product)
        jdbc.update("delete from point_balances where user_id = ?", buyer)
        mvc.perform(get("/api/v1/points").header("X-USER-ID", buyer))
            .andExpect(status().isInternalServerError).andExpect(jsonPath("$.meta.errorCode").value("Internal Server Error"))
        mvc.perform(post("/api/v1/points/charge").header("X-USER-ID", buyer).contentType(MediaType.APPLICATION_JSON).content("""{"amount":1000}"""))
            .andExpect(status().isInternalServerError)
        mvc.perform(post("/api/v1/orders/$order/confirm").header("X-USER-ID", buyer))
            .andExpect(status().isInternalServerError)
        assertThat(number("select count(*) from point_balances")).isZero()
        assertThat(stock(product)).isEqualTo(1)
        assertThat(jdbc.queryForObject("select status from orders where id=?", String::class.java, order)).isEqualTo("DRAFT")
    }

    @Test
    fun `duplicate account initialization fails rather than resetting balance`() {
        val buyer = buyer(9000)
        assertThrows<org.springframework.dao.DataIntegrityViolationException> { tx { pointStorage.create(com.loopers.infrastructure.point.PointAccountEntity(buyer, 0)) } }
        assertThat(balance(buyer)).isEqualTo(9000)
    }

    @Test
    fun `failed user insert leaves neither aggregate`() {
        assertThrows<org.springframework.dao.DataIntegrityViolationException> { users.create("x".repeat(256)) }
        assertThat(number("select count(*) from users")).isZero()
        assertThat(number("select count(*) from point_balances")).isZero()
    }
}
