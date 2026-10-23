package com.loopers.application.transaction

import com.loopers.confirmWithTestCard
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

class BrandDeletionHttpTest : CommerceTransactionTestSupport() {
    @Test
    fun `brand deletion preserves paid history likes and rejects every new use of deleted products`() {
        val buyer = buyer(10_000)
        val brand = brands.create("브랜드").id
        val product = products.create(brand, "상품", 1000, 5).id
        val zero = products.create(brand, "품절", 1000, 0).id
        val paid = draft(buyer, product)
        orders.confirmWithTestCard(buyer, paid)
        val pending = draft(buyer, product)
        val paidBefore = orders.getForUser(buyer, paid)
        mvc.perform(post("/api/v1/products/$product/likes").header("X-USER-ID", buyer)).andExpect(status().isOk)
        mvc.perform(delete("/api-admin/v1/brands/$brand").with(user("admin").roles("ADMIN")).with(csrf()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.meta.result").value("SUCCESS"))
        assertThat(orders.getForUser(buyer, paid)).isEqualTo(paidBefore)
        assertThat(balance(buyer)).isEqualTo(10_000)
        assertThat(number("select count(*) from products where brand_id=? and deleted_at is not null", brand)).isEqualTo(2)
        assertThat(stock(zero)).isZero()
        assertThat(number("select count(*) from product_likes where product_id=?", product)).isEqualTo(1)
        mvc.perform(get("/api/v1/products?brandId=$brand")).andExpect(status().isOk).andExpect(jsonPath("$.data.totalElements").value(0))
        mvc.perform(get("/api/v1/products/$product")).andExpect(status().isNotFound)
        mvc.perform(post("/api/v1/products/$product/likes").header("X-USER-ID", buyer)).andExpect(status().isNotFound)
        mvc.perform(get("/api/v1/users/$buyer/likes").header("X-USER-ID", buyer))
            .andExpect(status().isOk).andExpect(jsonPath("$.data.totalElements").value(0))
        mvc.perform(
            post("/api/v1/orders").header("X-USER-ID", buyer).contentType(MediaType.APPLICATION_JSON)
            .content("""{"items":[{"productId":$product,"quantity":1}]}"""),
        )
            .andExpect(status().isNotFound)
        mvc.perform(post("/api/v1/orders/$pending/confirm").header("X-USER-ID", buyer).contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(com.loopers.testPaymentJson(pending)))
            .andExpect(status().isNotFound).andExpect(jsonPath("$.meta.errorCode").value("PRODUCT_NOT_FOUND"))
        mvc.perform(
            put("/api-admin/v1/products/$product").with(user("admin").roles("ADMIN")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("""{"name":"변경","price":2000}"""),
        )
            .andExpect(status().isNotFound)
        mvc.perform(
            put("/api-admin/v1/products/$product/stock").with(user("admin").roles("ADMIN")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("""{"stock":10}"""),
        )
            .andExpect(status().isNotFound)
        mvc.perform(delete("/api/v1/products/$product/likes").header("X-USER-ID", buyer)).andExpect(status().isOk)
        assertThat(number("select count(*) from product_likes where product_id=?", product)).isZero()
        assertThat(stock(product)).isEqualTo(4)
        assertThat(balance(buyer)).isEqualTo(10_000)
    }

    @Test
    fun `unauthorized deletion and other buyer confirmation preserve database`() {
        val buyer = buyer(10_000)
        val other = buyer(10_000)
        val brand = brands.create("브랜드").id
        val product = products.create(brand, "상품", 1000, 5).id
        val order = draft(buyer, product)
        mvc.perform(delete("/api-admin/v1/brands/$brand").with(csrf())).andExpect(status().isForbidden)
        mvc.perform(delete("/api-admin/v1/brands/$brand").with(user("buyer").roles("USER")).with(csrf()))
            .andExpect(status().isForbidden).andExpect(jsonPath("$.meta.errorCode").value("ADMIN_FORBIDDEN"))
        mvc.perform(post("/api/v1/orders/$order/confirm").header("X-USER-ID", other).contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(com.loopers.testPaymentJson(order)))
            .andExpect(status().isForbidden).andExpect(jsonPath("$.meta.errorCode").value("ORDER_NOT_OWNED"))
        assertThat(number("select count(*) from brands where id=? and deleted_at is null", brand)).isEqualTo(1)
        assertThat(stock(product)).isEqualTo(5)
        assertThat(balance(buyer)).isEqualTo(10_000)
        assertThat(orders.getForUser(buyer, order).status.name).isEqualTo("DRAFT")
    }
}
