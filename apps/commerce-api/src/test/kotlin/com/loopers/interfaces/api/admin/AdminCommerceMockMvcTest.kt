package com.loopers.interfaces.api.admin

import com.loopers.infrastructure.brand.BrandJpaEntity
import com.loopers.infrastructure.brand.BrandJpaRepository
import com.loopers.infrastructure.product.ProductJpaEntity
import com.loopers.infrastructure.product.ProductJpaRepository
import com.loopers.infrastructure.point.PointAccountJpaRepository
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
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
@AutoConfigureMockMvc
class AdminCommerceMockMvcTest @Autowired constructor(
    private val mvc: MockMvc,
    private val brands: BrandJpaRepository,
    private val products: ProductJpaRepository,
    private val users: UserJpaRepository,
    private val initialization: UserInitializationService,
    private val accounts: PointAccountJpaRepository,
    private val entityManager: EntityManager,
    private val entityManagerFactory: EntityManagerFactory,
    private val cleanUp: DatabaseCleanUp,
) {
    @AfterEach
    fun clean() = cleanUp.truncateAllTables()

    @Test
    fun `user fixture and brand product CRUD persist through HTTP`() {
        val fixtureUser = initialization.create("구매자")
        entityManager.clear()
        assertThat(users.findById(fixtureUser.id)).isPresent()
        assertThat(accounts.findById(fixtureUser.id).orElseThrow().balance).isZero()

        mvc.perform(post("/api-admin/v1/brands").with(user("admin").roles("ADMIN")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("""{"name":"  브랜드  "}"""))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.name").value("브랜드"))
        val brand = brands.findAll().single()
        mvc.perform(get("/api-admin/v1/brands/${brand.id}").with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.name").value("브랜드"))
        mvc.perform(get("/api-admin/v1/brands?size=1").with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.totalElements").value(1))
        mvc.perform(
            put("/api-admin/v1/brands/${brand.id}").with(user("admin").roles("ADMIN")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"수정 브랜드"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.name").value("수정 브랜드"))

        mvc.perform(
            post("/api-admin/v1/products").with(user("admin").roles("ADMIN")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("""{"brandId":${brand.id},"name":" 상품 ","price":7000,"stock":5}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.stock").value(5))
        val product = products.findAll().single()
        mvc.perform(get("/api-admin/v1/products?brandId=${brand.id}").with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.content[0].brand.name").value("수정 브랜드"))

        mvc.perform(
            put("/api-admin/v1/products/${product.id}").with(user("admin").roles("ADMIN")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("""{"name":"수정 상품","price":6000}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.brand.id").value(brand.id))
            .andExpect(jsonPath("$.data.stock").value(5))
        mvc.perform(get("/api/v1/products/${product.id}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.name").value("수정 상품"))
            .andExpect(jsonPath("$.data.price").value(6000))
        mvc.perform(
            put("/api-admin/v1/products/${product.id}/stock").with(user("admin").roles("ADMIN")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("""{"stock":0}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.stock").value(0))
        entityManager.clear()
        assertThat(brands.findById(brand.id).orElseThrow().deletedAt).isNull()
        mvc.perform(get("/api-admin/v1/products/${product.id}").with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.name").value("수정 상품"))
        mvc.perform(delete("/api-admin/v1/products/${product.id}").with(user("admin").roles("ADMIN")).with(csrf()))
            .andExpect(status().isOk)
        mvc.perform(get("/api-admin/v1/products/${product.id}").with(user("admin").roles("ADMIN")))
            .andExpect(status().isNotFound)
        mvc.perform(
            put("/api-admin/v1/products/${product.id}/stock").with(user("admin").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("""{"stock":10}"""),
        ).andExpect(status().isNotFound)
        mvc.perform(
            put("/api-admin/v1/products/${product.id}").with(user("admin").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("""{"name":"복원 시도","price":1}"""),
        ).andExpect(status().isNotFound)
        mvc.perform(delete("/api-admin/v1/brands/${brand.id}").with(user("admin").roles("ADMIN")).with(csrf()))
            .andExpect(status().isOk)
        mvc.perform(get("/api/v1/brands/${brand.id}"))
            .andExpect(status().isNotFound)
        entityManager.clear()
        assertThat(products.findById(product.id).orElseThrow().deletedAt).isNotNull()
        assertThat(brands.findById(brand.id).orElseThrow().deletedAt).isNotNull()
    }

    @Test
    fun `admin boundary and invalid payloads`() {
        val brand = brands.save(BrandJpaEntity("브랜드"))
        val product = products.save(ProductJpaEntity(brand.id, "상품", 100, 1))
        mvc.perform(get("/api-admin/v1/products/${product.id}")).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.meta.errorCode").value("ADMIN_FORBIDDEN"))
        mvc.perform(get("/api-admin/v1/products/${product.id}").with(user("buyer").roles("USER"))).andExpect(status().isForbidden)
        mvc.perform(
            put("/api-admin/v1/products/${product.id}/stock").with(user("buyer").roles("USER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("""{"stock":9}"""),
        ).andExpect(status().isForbidden)
        mvc.perform(
            put("/api-admin/v1/products/${product.id}/stock").with(user("admin").roles("ADMIN")).contentType(MediaType.APPLICATION_JSON)
            .content("""{"stock":9}"""),
        )
            .andExpect(status().isForbidden)
        mvc.perform(
            put("/api-admin/v1/products/${product.id}/stock").with(user("admin").roles("ADMIN")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("""{"stock":-1}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.meta.errorCode").value("INVALID_STOCK"))
        mvc.perform(
            post("/api-admin/v1/brands").with(user("admin").roles("ADMIN")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{}"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.meta.errorCode").value("INVALID_REQUEST"))
        entityManager.clear()
        assertThat(products.findById(product.id).orElseThrow().stock).isEqualTo(1)
    }

    @Test
    fun `admin product page loads brands in a bounded number of queries`() {
        repeat(12) { index ->
            val brand = brands.save(BrandJpaEntity("브랜드$index"))
            products.save(ProductJpaEntity(brand.id, "상품$index", 100, 1))
        }
        val statistics = entityManagerFactory.unwrap(SessionFactory::class.java).statistics
        val statisticsWereEnabled = statistics.isStatisticsEnabled
        statistics.isStatisticsEnabled = true
        try {
            statistics.clear()
            mvc.perform(get("/api-admin/v1/products?size=12").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.content.length()").value(12))
            assertThat(statistics.prepareStatementCount).isLessThanOrEqualTo(5)
        } finally {
            statistics.isStatisticsEnabled = statisticsWereEnabled
        }
    }
}
