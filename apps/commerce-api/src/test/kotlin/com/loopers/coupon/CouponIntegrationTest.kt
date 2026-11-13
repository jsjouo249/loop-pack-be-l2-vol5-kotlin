package com.loopers.coupon

import com.fasterxml.jackson.databind.ObjectMapper
import com.loopers.application.coupon.CouponAdmissionService
import com.loopers.application.coupon.CouponApplicationService
import com.loopers.application.coupon.CouponCampaignPreparation
import com.loopers.application.coupon.CouponProjectionWorker
import com.loopers.application.user.UserInitializationService
import com.loopers.config.redis.RedisConfig
import com.loopers.domain.coupon.CouponAdmissionException
import com.loopers.domain.coupon.CouponCampaign
import com.loopers.domain.coupon.CouponCampaignRepository
import com.loopers.domain.coupon.IssuedCouponRepository
import com.loopers.infrastructure.coupon.CouponDataKeys
import com.loopers.infrastructure.coupon.CouponPermitKeys
import com.loopers.infrastructure.coupon.JpaIssuedCouponRepository
import com.loopers.infrastructure.coupon.RedisCouponAuthority
import com.loopers.utils.DatabaseCleanUp
import io.micrometer.core.instrument.MeterRegistry
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doCallRealMethod
import org.mockito.kotlin.doThrow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 이 namespace 정리는 테스트 소유 Testcontainers Redis에만 수행한다. 운영·로컬 endpoint에서 호출하지 않는다. */
internal fun clearCouponTestState(redis: RedisTemplate<String, String>) {
    val keys = redis.keys("coupon:ssot:{issuance}:*") + setOf(CouponPermitKeys.ACTIVE, CouponPermitKeys.STATE)
    redis.delete(keys)
    redis.connectionFactory!!.connection.use { connection ->
        connection.serverCommands().setConfig("appendonly", "yes")
        connection.serverCommands().setConfig("appendfsync", "always")
        connection.serverCommands().setConfig("maxmemory-policy", "noeviction")
    }
}

@SpringBootTest(properties = ["coupon.projection.enabled=false", "coupon.admission.wait-timeout-ms=2000"])
@AutoConfigureMockMvc
class CouponIntegrationTest @Autowired constructor(
    private val mvc: MockMvc,
    private val mapper: ObjectMapper,
    private val coupons: CouponApplicationService,
    private val admission: CouponAdmissionService,
    private val preparation: CouponCampaignPreparation,
    private val worker: CouponProjectionWorker,
    private val campaigns: CouponCampaignRepository,
    private val issued: IssuedCouponRepository,
    private val initialization: UserInitializationService,
    private val em: EntityManager,
    private val manager: PlatformTransactionManager,
    private val cleanup: DatabaseCleanUp,
    private val meters: MeterRegistry,
    @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) private val redis: RedisTemplate<String, String>,
) {
    @MockitoSpyBean
    private lateinit var insertBoundary: JpaIssuedCouponRepository

    @MockitoSpyBean
    private lateinit var authorityBoundary: RedisCouponAuthority

    @MockitoSpyBean
    private lateinit var admissionBoundary: CouponAdmissionService

    private val campaignId = 1L
    private val path = "/api/v1/coupon-campaigns/$campaignId/issues"

    @BeforeEach
    fun setup() {
        admission.close()
        clearCouponTestState(redis)
        cleanup.truncateAllTables()
        preparation.prepare(bootstrapConfirmed = true)
        preparation.openAfterQuiescence(true)
    }

    @AfterEach
    fun clean() {
        admission.close()
        clearCouponTestState(redis)
        cleanup.truncateAllTables()
    }

    @Test
    fun `HTTP issuance and owned list succeed before DB projection and retries return the stored result`() {
        val owner = initialization.create("소유자").id
        val other = initialization.create("다른 사용자").id
        val first = mvc.perform(post(path).header("X-USER-ID", owner)).andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.benefitDescription").value("5,000원 할인권"))
            .andReturn().response.contentAsString
        admission.close()

        val retry = mvc.perform(post(path).header("X-USER-ID", owner)).andExpect(status().isOk)
            .andReturn().response.contentAsString
        assertThat(mapper.readTree(retry)).isEqualTo(mapper.readTree(first))
        mvc.perform(get("/api/v1/coupons").header("X-USER-ID", owner))
            .andExpect(status().isOk).andExpect(jsonPath("$.data.length()").value(1))
        mvc.perform(get("/api/v1/coupons").header("X-USER-ID", other))
            .andExpect(status().isOk).andExpect(jsonPath("$.data.length()").value(0))
        assertThat(issued.findByUser(owner)).isEmpty()
        assertThat(campaigns.find(campaignId)).isNull()

        assertThat(worker.runOnce()).isEqualTo(1)
        TransactionTemplate(manager).execute {
            em.flush()
            em.clear()
            val stored = issued.findByUser(owner).single()
            assertThat(stored.id).isEqualTo(mapper.readTree(first).path("data").path("couponId").asLong())
            assertThat(stored).isEqualTo(coupons.owned(owner).single())
            assertThat(campaigns.find(campaignId)!!.issuedCount).isEqualTo(1)
        }
        assertThat(worker.runOnce()).isZero()
    }

    @Test
    fun `requester authentication missing campaign and inactive campaign keep HTTP contracts`() {
        val user = initialization.create("구매자").id
        mvc.perform(get("/api/v1/coupons")).andExpect(status().isUnauthorized)
        mvc.perform(get("/api/v1/coupons").header("X-USER-ID", "invalid")).andExpect(status().isBadRequest)
        mvc.perform(get("/api/v1/coupons").header("X-USER-ID", Long.MAX_VALUE)).andExpect(status().isNotFound)
        mvc.perform(post("/api/v1/coupon-campaigns/0/issues").header("X-USER-ID", user)).andExpect(status().isBadRequest)
        mvc.perform(post("/api/v1/coupon-campaigns/999/issues").header("X-USER-ID", user)).andExpect(status().isNotFound)
        redis.opsForHash<String, String>().put(CouponDataKeys.campaign(1), "enabled", "0")

        mvc.perform(post(path).header("X-USER-ID", user)).andExpect(status().isConflict)
            .andExpect(jsonPath("$.meta.errorCode").value("COUPON_CAMPAIGN_INACTIVE"))
        assertThat(checkNotNull(redis.opsForSet().size(CouponPermitKeys.ACTIVE))).isZero()
    }

    /** 세 HTTP 실행을 획득 직후 유지해 네 번째 대기·한 자리 반환·실제 Redis 발급을 확인한다. */
    @Test
    fun `three held executions make the fourth wait then reuse a returned slot without DB issuance`() {
        val users = (1..4).map { initialization.create("구매자$it").id }
        val held = CountDownLatch(3)
        val release = List(3) { CountDownLatch(1) }
        val sequence = AtomicInteger()
        val pool = Executors.newFixedThreadPool(4)
        doAnswer { invocation ->
            val permit = invocation.callRealMethod()
            val index = sequence.getAndIncrement()
            if (index < 3) {
                held.countDown()
                check(release[index].await(10, TimeUnit.SECONDS))
            }

            permit
        }.`when`(admissionBoundary).acquire()
        try {
            val pending = users.take(3).map { user ->
                pool.submit(Callable { mvc.perform(post(path).header("X-USER-ID", user)).andReturn() })
            }
            check(held.await(5, TimeUnit.SECONDS))
            val fourth = pool.submit(Callable { mvc.perform(post(path).header("X-USER-ID", users[3])).andReturn() })
            val waitDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while (meters.get("coupon.admission.waiting").gauge().value() != 1.0 && System.nanoTime() < waitDeadline) Thread.sleep(1)
            assertThat(meters.get("coupon.admission.waiting").gauge().value()).isEqualTo(1.0)
            assertThat(fourth.isDone).isFalse()
            assertThat(checkNotNull(redis.opsForSet().size(CouponPermitKeys.ACTIVE))).isEqualTo(3)
            assertThat(redis.opsForHash<String, String>().get(CouponDataKeys.campaign(1), "issued")).isEqualTo("0")

            release[0].countDown()
            assertThat(fourth.get(5, TimeUnit.SECONDS).response.status).isEqualTo(201)
            release.forEach { it.countDown() }
            pending.forEach { assertThat(it.get(5, TimeUnit.SECONDS).response.status).isEqualTo(201) }
        } finally {
            release.forEach { it.countDown() }
            pool.shutdownNow()
            pool.awaitTermination(5, TimeUnit.SECONDS)
        }

        assertThat(checkNotNull(redis.opsForSet().size(CouponPermitKeys.ACTIVE))).isZero()
        assertThat(redis.opsForHash<String, String>().get(CouponDataKeys.campaign(1), "issued")).isEqualTo("4")
        users.forEach { assertThat(issued.findByUser(it)).isEmpty() }
        assertThat(worker.runOnce()).isEqualTo(4)
        assertThat(campaigns.find(1)!!.issuedCount).isEqualTo(4)
    }

    @Test
    fun `HTTP waiting deadline returns 429 without issuing or returning someone else's token`() {
        val user = initialization.create("대기 구매자").id
        val held = List(3) { admission.acquire() }
        try {
            mvc.perform(post(path).header("X-USER-ID", user)).andExpect(status().isTooManyRequests)
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.meta.errorCode").value("COUPON_ADMISSION_FULL"))

            assertThat(checkNotNull(redis.opsForSet().size(CouponPermitKeys.ACTIVE))).isEqualTo(3)
            assertThat(redis.opsForHash<String, String>().get(CouponDataKeys.campaign(1), "issued")).isEqualTo("0")
            assertThat(coupons.owned(user)).isEmpty()
        } finally {
            held.forEach { admission.complete(it, true) }
        }
    }

    @Test
    fun `concurrent same user issues create one Redis fact event and DB effect`() {
        val user = initialization.create("구매자").id
        val pool = Executors.newFixedThreadPool(3)
        val start = CountDownLatch(1)
        try {
            val pending = (1..3).map {
                pool.submit(
                    Callable {
                start.await()
                coupons.issue(user, 1)
            },
                )
            }
            start.countDown()
            val results = pending.map { it.get(5, TimeUnit.SECONDS) }

            assertThat(results.map { it.coupon.id }.toSet()).hasSize(1)
            assertThat(results.count { it.created }).isEqualTo(1)
            assertThat(checkNotNull(redis.opsForStream<String, String>().size(CouponDataKeys.EVENTS))).isEqualTo(1)
            assertThat(worker.runOnce()).isEqualTo(1)
            assertThat(campaigns.find(1)!!.issuedCount).isEqualTo(1)
            assertThat(issued.findByUser(user)).hasSize(1)
        } finally {
            start.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `DB insert failure rolls back projection and leaves original Redis coupon and pending event intact`() {
        val user = initialization.create("구매자").id
        val fact = coupons.issue(user, 1).coupon
        doThrow(IllegalStateException("insert failed")).`when`(insertBoundary).insert(any())

        assertThat(worker.runOnce()).isZero()
        assertThat(issued.findByUser(user)).isEmpty()
        assertThat(campaigns.find(1)).isNull()
        assertThat(coupons.owned(user)).containsExactly(fact)
        assertThat(redis.opsForHash<String, String>().get(CouponDataKeys.campaign(1), "issued")).isEqualTo("1")

        doCallRealMethod().`when`(insertBoundary).insert(any())
        assertThat(worker.runOnce()).isEqualTo(1)
        assertThat(issued.findByUser(user)).containsExactly(fact)
    }

    @Test
    fun `commit followed by ACK failure replays idempotently without increasing projected count twice`() {
        val user = initialization.create("구매자").id
        val fact = coupons.issue(user, 1).coupon
        doThrow(IllegalStateException("ACK response lost")).`when`(authorityBoundary).acknowledge(any())

        assertThat(worker.runOnce()).isZero()
        assertThat(issued.findByUser(user)).containsExactly(fact)
        assertThatThrownBy { admission.acquire() }.isInstanceOf(CouponAdmissionException::class.java)

        doCallRealMethod().`when`(authorityBoundary).acknowledge(any())
        assertThat(worker.runOnce()).isEqualTo(1)
        assertThat(campaigns.find(1)!!.issuedCount).isEqualTo(1)
        assertThat(issued.findByUser(user)).hasSize(1)
        preparation.openAfterQuiescence(true)
        assertThat(coupons.issue(user, 1).coupon).isEqualTo(fact)
    }

    @Test
    fun `reopening never clears dirty authority data or resets issued quantity`() {
        val user = initialization.create("구매자").id
        coupons.issue(user, 1)
        admission.close()
        redis.opsForHash<String, String>().put(CouponDataKeys.META, "health", "DIRTY")

        assertThatThrownBy { preparation.openAfterQuiescence(true) }.isInstanceOf(CouponAdmissionException::class.java)
        mvc.perform(get("/api/v1/coupons").header("X-USER-ID", user)).andExpect(status().isServiceUnavailable)
        assertThat(redis.opsForHash<String, String>().get(CouponDataKeys.campaign(1), "issued")).isEqualTo("1")
        assertThat(redis.opsForHash<String, String>().get(CouponDataKeys.META, "health")).isEqualTo("DIRTY")
    }

    @Test
    fun `missing registered campaign or owner cannot be silently initialized as empty`() {
        val user = initialization.create("구매자").id
        coupons.issue(user, 1)
        admission.close()
        redis.delete(CouponDataKeys.owner(user))

        mvc.perform(post(path).header("X-USER-ID", user)).andExpect(status().isServiceUnavailable)
        assertThat(redis.opsForHash<String, String>().get(CouponDataKeys.campaign(1), "issued")).isEqualTo("1")
        redis.delete(CouponDataKeys.campaign(1))
        assertThatThrownBy { preparation.prepare() }.isInstanceOf(CouponAdmissionException::class.java)
        assertThat(redis.hasKey(CouponDataKeys.campaign(1))).isFalse()
    }

    @Test
    fun `stored result survives campaign deactivation and owned ordering is stable`() {
        admission.close()
        preparation.prepare(CouponCampaign("다른 행사", "다른 혜택", 2, id = 2))
        preparation.openAfterQuiescence(true)
        val user = initialization.create("구매자").id
        val first = coupons.issue(user, 1).coupon
        val second = coupons.issue(user, 2).coupon
        redis.opsForHash<String, String>().put(CouponDataKeys.campaign(1), "enabled", "0")

        assertThat(coupons.issue(user, 1).coupon).isEqualTo(first)
        assertThat(coupons.owned(user)).containsExactly(second, first)
    }
}
