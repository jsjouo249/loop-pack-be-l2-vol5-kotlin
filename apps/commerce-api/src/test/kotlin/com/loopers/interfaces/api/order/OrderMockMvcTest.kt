package com.loopers.interfaces.api.order

import com.fasterxml.jackson.databind.ObjectMapper
import com.loopers.infrastructure.brand.BrandJpaEntity
import com.loopers.infrastructure.brand.BrandJpaRepository
import com.loopers.infrastructure.product.ProductJpaEntity
import com.loopers.infrastructure.order.OrderJpaRepository
import com.loopers.infrastructure.point.PointAccountJpaEntity
import com.loopers.infrastructure.point.PointAccountJpaRepository
import com.loopers.infrastructure.product.ProductJpaRepository
import com.loopers.application.user.UserInitializationService
import com.loopers.infrastructure.user.UserJpaRepository
import com.loopers.utils.DatabaseCleanUp
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import org.assertj.core.api.Assertions.assertThat
import org.hibernate.SessionFactory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
@AutoConfigureMockMvc
class OrderMockMvcTest @Autowired constructor(
    private val mvc: MockMvc,
    private val mapper: ObjectMapper,
    private val brands: BrandJpaRepository,
    private val products: ProductJpaRepository,
    private val users: UserJpaRepository,
    private val initialization: UserInitializationService,
    private val accounts: PointAccountJpaRepository,
    private val orders: OrderJpaRepository,
    private val jdbc: JdbcTemplate,
    private val entityManager: EntityManager,
    private val entityManagerFactory: EntityManagerFactory,
    private val cleanUp: DatabaseCleanUp,
) {
    @AfterEach
    fun clean() = cleanUp.truncateAllTables()

    @Test
    fun `charge draft confirm and read snapshots end to end`() {
        val buyer = initialization.create("구매자")
        val brand = brands.save(BrandJpaEntity("브랜드"))
        val first = products.save(ProductJpaEntity(brand.id, "첫 상품", 1000, 5))
        val second = products.save(ProductJpaEntity(brand.id, "두번째 상품", 2000, 3))
        mvc.perform(
            post("/api/v1/points/charge").header("X-USER-ID", buyer.id).contentType(MediaType.APPLICATION_JSON)
            .content("""{"amount":10000}"""),
        )
            .andExpect(status().isOk)
        val createResponse = mvc.perform(
            post("/api/v1/orders").header("X-USER-ID", buyer.id).contentType(MediaType.APPLICATION_JSON)
            .content("""{"items":[{"productId":${first.id},"quantity":2},{"productId":${first.id},"quantity":1},{"productId":${second.id},"quantity":2}]}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.status").value("DRAFT"))
            .andExpect(jsonPath("$.data.totalAmount").value(7000))
            .andExpect(jsonPath("$.data.items[0].quantity").value(3))
            .andReturn()
        val orderId = mapper.readTree(createResponse.response.contentAsString).path("data").path("id").asLong()
        entityManager.clear()
        assertThat(accounts.findById(buyer.id).orElseThrow().balance).isEqualTo(10000)
        assertThat(products.findById(first.id).orElseThrow().stock).isEqualTo(5)
        assertThat(jdbc.queryForObject("select count(*) from order_items where order_id = ?", Long::class.java, orderId) ?: 0L).isEqualTo(2)

        first.price = 3000
        products.saveAndFlush(first)
        val otherBuyer = initialization.create("다른 구매자")
        mvc.perform(get("/api/v1/orders/$orderId").header("X-USER-ID", otherBuyer.id))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.meta.errorCode").value("ORDER_NOT_OWNED"))
        mvc.perform(post("/api/v1/orders/$orderId/confirm").header("X-USER-ID", otherBuyer.id))
            .andExpect(status().isForbidden)

        mvc.perform(post("/api/v1/orders/$orderId/confirm").header("X-USER-ID", buyer.id))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
            .andExpect(jsonPath("$.data.paymentAmount").value(7000))
        mvc.perform(get("/api/v1/orders/$orderId").header("X-USER-ID", buyer.id))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.items[0].unitPrice").value(1000))
        mvc.perform(get("/api/v1/orders").header("X-USER-ID", buyer.id))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.totalElements").value(1))
        mvc.perform(get("/api-admin/v1/orders/$orderId").with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.userId").value(buyer.id))
        mvc.perform(get("/api-admin/v1/orders?userId=${buyer.id}").with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.totalElements").value(1))
        entityManager.clear()
        assertThat(accounts.findById(buyer.id).orElseThrow().balance).isEqualTo(3000)
        assertThat(products.findById(first.id).orElseThrow().stock).isEqualTo(2)
        assertThat(products.findById(second.id).orElseThrow().stock).isEqualTo(1)
        mvc.perform(post("/api/v1/orders/$orderId/confirm").header("X-USER-ID", buyer.id))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.meta.errorCode").value("ORDER_ALREADY_CONFIRMED"))
        entityManager.clear()
        assertThat(orders.findById(orderId).orElseThrow().paymentAmount).isEqualTo(7000)
        assertThat(accounts.findById(buyer.id).orElseThrow().balance).isEqualTo(3000)
        assertThat(products.findById(first.id).orElseThrow().stock).isEqualTo(2)
        assertThat(products.findById(second.id).orElseThrow().stock).isEqualTo(1)
    }

    @Test
    fun `insufficient stock keeps draft order points and stock unchanged`() {
        val buyer = initialization.create("구매자")
        val brand = brands.save(BrandJpaEntity("브랜드"))
        val first = products.save(ProductJpaEntity(brand.id, "첫 상품", 1000, 3))
        val product = products.save(ProductJpaEntity(brand.id, "두번째 상품", 1000, 1))
        mvc.perform(
            post("/api/v1/points/charge").header("X-USER-ID", buyer.id).contentType(MediaType.APPLICATION_JSON)
            .content("""{"amount":10000}"""),
        )
            .andExpect(status().isOk)
        val response = mvc.perform(
            post("/api/v1/orders").header("X-USER-ID", buyer.id).contentType(MediaType.APPLICATION_JSON)
            .content("""{"items":[{"productId":${first.id},"quantity":2},{"productId":${product.id},"quantity":2}]}"""),
        )
            .andExpect(status().isCreated)
            .andReturn()
        val orderId = mapper.readTree(response.response.contentAsString).path("data").path("id").asLong()
        mvc.perform(post("/api/v1/orders/$orderId/confirm").header("X-USER-ID", buyer.id))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.meta.errorCode").value("INSUFFICIENT_STOCK"))
        entityManager.clear()
        assertThat(orders.findById(orderId).orElseThrow().status.name).isEqualTo("DRAFT")
        assertThat(products.findById(first.id).orElseThrow().stock).isEqualTo(3)
        assertThat(products.findById(product.id).orElseThrow().stock).isEqualTo(1)
        assertThat(accounts.findById(buyer.id).orElseThrow().balance).isEqualTo(10000)
    }

    @Test
    fun `insufficient points preserve stock and draft order`() {
        val buyer = initialization.create("구매자")
        val brand = brands.save(BrandJpaEntity("브랜드"))
        val product = products.save(ProductJpaEntity(brand.id, "상품", 1000, 1))
        val response = mvc.perform(
            post("/api/v1/orders").header("X-USER-ID", buyer.id).contentType(MediaType.APPLICATION_JSON)
                .content("""{"items":[{"productId":${product.id},"quantity":1}]}"""),
        )
            .andExpect(status().isCreated)
            .andReturn()
        val orderId = mapper.readTree(response.response.contentAsString).path("data").path("id").asLong()
        mvc.perform(post("/api/v1/orders/$orderId/confirm").header("X-USER-ID", buyer.id))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.meta.errorCode").value("INSUFFICIENT_POINTS"))
        entityManager.clear()
        assertThat(orders.findById(orderId).orElseThrow().status.name).isEqualTo("DRAFT")
        assertThat(products.findById(product.id).orElseThrow().stock).isEqualTo(1)
        assertThat(accounts.findById(buyer.id).orElseThrow().balance).isZero()
    }

    @Test
    fun `zero amount order confirms using existing zero balance account`() {
        val buyer = initialization.create("구매자")
        val brand = brands.save(BrandJpaEntity("브랜드"))
        val product = products.save(ProductJpaEntity(brand.id, "무료 상품", 0, 1))
        val response = mvc.perform(
            post("/api/v1/orders").header("X-USER-ID", buyer.id).contentType(MediaType.APPLICATION_JSON)
                .content("""{"items":[{"productId":${product.id},"quantity":1}]}"""),
        ).andExpect(status().isCreated).andReturn()
        val orderId = mapper.readTree(response.response.contentAsString).path("data").path("id").asLong()

        mvc.perform(post("/api/v1/orders/$orderId/confirm").header("X-USER-ID", buyer.id))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.paymentAmount").value(0))
        entityManager.clear()
        assertThat(accounts.findById(buyer.id).orElseThrow().balance).isZero()
        assertThat(products.findById(product.id).orElseThrow().stock).isZero()
    }

    @Test
    fun `deleted draft product rejects confirmation without changing order stock or points`() {
        val buyer = initialization.create("구매자")
        accounts.save(PointAccountJpaEntity(buyer.id, 10_000))
        val brand = brands.save(BrandJpaEntity("브랜드"))
        val first = products.save(ProductJpaEntity(brand.id, "첫 상품", 1000, 3))
        val second = products.save(ProductJpaEntity(brand.id, "삭제할 상품", 2000, 2))
        val response = mvc.perform(
            post("/api/v1/orders").header("X-USER-ID", buyer.id).contentType(MediaType.APPLICATION_JSON)
                .content("""{"items":[{"productId":${first.id},"quantity":2},{"productId":${second.id},"quantity":1}]}"""),
        ).andExpect(status().isCreated).andReturn()
        val orderId = mapper.readTree(response.response.contentAsString).path("data").path("id").asLong()

        mvc.perform(delete("/api-admin/v1/products/${second.id}").with(user("admin").roles("ADMIN")).with(csrf()))
            .andExpect(status().isOk)
        mvc.perform(
            post("/api/v1/orders").header("X-USER-ID", buyer.id).contentType(MediaType.APPLICATION_JSON)
                .content("""{"items":[{"productId":${second.id},"quantity":1}]}"""),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.meta.errorCode").value("PRODUCT_NOT_FOUND"))
        mvc.perform(post("/api/v1/orders/$orderId/confirm").header("X-USER-ID", buyer.id))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.meta.errorCode").value("PRODUCT_NOT_FOUND"))

        entityManager.clear()
        val savedOrder = orders.findById(orderId).orElseThrow()
        assertThat(savedOrder.status.name).isEqualTo("DRAFT")
        assertThat(savedOrder.paymentAmount).isNull()
        assertThat(savedOrder.paymentResult).isNull()
        assertThat(savedOrder.confirmedAt).isNull()
        assertThat(jdbc.queryForObject("select count(*) from order_items where order_id = ?", Long::class.java, orderId) ?: 0L)
            .isEqualTo(2)
        assertThat(products.findById(first.id).orElseThrow().stock).isEqualTo(3)
        assertThat(products.findById(second.id).orElseThrow().stock).isEqualTo(2)
        assertThat(products.findById(second.id).orElseThrow().deletedAt).isNotNull()
        assertThat(accounts.findById(buyer.id).orElseThrow().balance).isEqualTo(10_000)
    }

    @Test
    fun `duplicate quantity overflow creates no order`() {
        val buyer = initialization.create("구매자")
        val brand = brands.save(BrandJpaEntity("브랜드"))
        val product = products.save(ProductJpaEntity(brand.id, "상품", 1000, 1))
        mvc.perform(
            post("/api/v1/orders").header("X-USER-ID", buyer.id).contentType(MediaType.APPLICATION_JSON)
                .content("""{"items":[{"productId":${product.id},"quantity":2147483647},{"productId":${product.id},"quantity":1}]}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.meta.errorCode").value("INVALID_QUANTITY"))
        assertThat(orders.count()).isZero()
    }

    @Test
    fun `multi item confirmation does not flush for every product`() {
        val buyer = initialization.create("구매자")
        accounts.save(PointAccountJpaEntity(buyer.id, 20_000))
        val brand = brands.save(BrandJpaEntity("브랜드"))
        val productIds = (0 until 12).map { index ->
            products.save(ProductJpaEntity(brand.id, "상품$index", 1000, 1)).id
        }
        val items = productIds.joinToString(",") { """{"productId":$it,"quantity":1}""" }
        val response = mvc.perform(
            post("/api/v1/orders").header("X-USER-ID", buyer.id).contentType(MediaType.APPLICATION_JSON)
                .content("""{"items":[$items]}"""),
        ).andExpect(status().isCreated).andReturn()
        val orderId = mapper.readTree(response.response.contentAsString).path("data").path("id").asLong()

        val statistics = entityManagerFactory.unwrap(SessionFactory::class.java).statistics
        val statisticsWereEnabled = statistics.isStatisticsEnabled
        statistics.isStatisticsEnabled = true
        try {
            statistics.clear()
            mvc.perform(post("/api/v1/orders/$orderId/confirm").header("X-USER-ID", buyer.id))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
            assertThat(statistics.flushCount).isLessThanOrEqualTo(4)
        } finally {
            statistics.isStatisticsEnabled = statisticsWereEnabled
        }
        entityManager.clear()
        assertThat(products.findAllById(productIds)).allMatch { it.stock == 0 }
        assertThat(accounts.findById(buyer.id).orElseThrow().balance).isEqualTo(8_000)
    }
}
