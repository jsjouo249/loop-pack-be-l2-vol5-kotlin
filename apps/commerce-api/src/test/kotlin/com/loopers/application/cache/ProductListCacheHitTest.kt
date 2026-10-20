package com.loopers.application.cache

import com.loopers.application.transaction.CapturedSql
import com.loopers.application.transaction.CommerceTransactionTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

class ProductListCacheHitTest : CommerceTransactionTestSupport() {
    @Test
    fun `repeated public list uses one bulk visibility query on hit`() {
        val id = product(100, 3)
        val url = "/api/v1/products?sort=price_asc"
        val first = mvc.perform(get(url)).andExpect(status().isOk).andReturn().response.contentAsString
        CapturedSql.statements.clear()
        val second = mvc.perform(get(url)).andExpect(status().isOk).andReturn().response.contentAsString
        assertThat(second).isEqualTo(first).contains(id.toString())
        assertThat(CapturedSql.statements.filter { it.startsWith("select ") }).hasSize(1)
    }
}
