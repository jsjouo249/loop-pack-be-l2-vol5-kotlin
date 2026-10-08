package com.loopers.interfaces.api.point

import com.loopers.infrastructure.point.PointAccountJpaRepository
import com.loopers.application.user.UserInitializationService
import com.loopers.infrastructure.user.UserJpaRepository
import com.loopers.utils.DatabaseCleanUp
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
@AutoConfigureMockMvc
class PointMockMvcTest @Autowired constructor(
    private val mvc: MockMvc,
    private val users: UserJpaRepository,
    private val initialization: UserInitializationService,
    private val accounts: PointAccountJpaRepository,
    private val entityManager: EntityManager,
    private val cleanUp: DatabaseCleanUp,
) {
    @AfterEach
    fun clean() = cleanUp.truncateAllTables()

    @Test
    fun `new user has a zero balance account`() {
        val user = initialization.create("구매자")
        mvc.perform(get("/api/v1/points").header("X-USER-ID", user.id))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.balance").value(0))
        mvc.perform(
            post("/api/v1/points/charge").header("X-USER-ID", user.id).contentType(MediaType.APPLICATION_JSON)
                .content("""{"amount":0}"""),
        ).andExpect(status().isBadRequest)
        assertThat(accounts.findById(user.id).orElseThrow().balance).isZero()
    }

    @Test
    fun `charge persists balance and rejects invalid amount`() {
        val user = initialization.create("구매자")
        mvc.perform(
            post("/api/v1/points/charge").header("X-USER-ID", user.id).contentType(MediaType.APPLICATION_JSON)
            .content("""{"amount":10000}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.balance").value(10000))
        mvc.perform(
            post("/api/v1/points/charge").header("X-USER-ID", user.id).contentType(MediaType.APPLICATION_JSON)
            .content("""{"amount":0}"""),
        )
            .andExpect(status().isBadRequest)
        for (body in listOf("{}", """{"amount":"invalid"}""", """{"amount":"100"}""", """{"amount":1.5}""", """{"amount":9223372036854775808}""")) {
            mvc.perform(
                post("/api/v1/points/charge").header("X-USER-ID", user.id).contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            )
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.meta.errorCode").value("INVALID_REQUEST"))
        }
        mvc.perform(get("/api/v1/points").header("X-USER-ID", user.id))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.balance").value(10000))
        entityManager.clear()
        assertThat(accounts.findById(user.id).orElseThrow().balance).isEqualTo(10000)
        assertThat(accounts.count()).isEqualTo(1)
    }
}
