package com.loopers.coupon

import com.loopers.application.coupon.CouponAdmissionService
import com.loopers.application.coupon.CouponCampaignPreparation
import com.loopers.application.coupon.CouponProjectionWorker
import com.loopers.application.coupon.CouponApplicationService
import com.loopers.application.user.UserInitializationService
import com.loopers.config.redis.RedisConfig
import com.loopers.domain.coupon.CouponAdmissionException
import com.loopers.domain.coupon.CouponCampaignRepository
import com.loopers.domain.coupon.IssuedCouponRepository
import com.loopers.infrastructure.coupon.RedisCouponPermits
import com.loopers.infrastructure.coupon.CouponPermitKeys
import com.loopers.utils.DatabaseCleanUp
import com.github.dockerjava.api.model.ExposedPort
import com.github.dockerjava.api.model.PortBinding
import com.github.dockerjava.api.model.Ports
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doCallRealMethod
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/** 테스트 소유 Redis endpoint를 실제 중단·재시작해 HTTP 503·DB 보존·명시적 재개를 검증한다. Docker가 필요하다. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CouponRedisFailureTest @Autowired constructor(
    private val mvc: MockMvc,
    private val coupons: CouponApplicationService,
    private val admission: CouponAdmissionService,
    private val preparation: CouponCampaignPreparation,
    private val worker: CouponProjectionWorker,
    private val campaigns: CouponCampaignRepository,
    private val issued: IssuedCouponRepository,
    private val users: UserInitializationService,
    private val cleanup: DatabaseCleanUp,
    @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) private val redis: RedisTemplate<String, String>,
) {
    companion object {
        // Docker 재시작이 호스트 포트를 다시 배정하지 않도록 테스트 전용 빈 포트를 명시적으로 바인딩한다.
        private val endpointPort = ServerSocket(0).use { it.localPort }

        @Container
        @JvmStatic
        val server = CouponRedisNode().withExposedPorts(6379)
            .withCreateContainerCmdModifier { command ->
                command.hostConfig!!.withPortBindings(PortBinding(Ports.Binding.bindPort(endpointPort), ExposedPort.tcp(6379)))
            }
            .withCommand("redis-server", "--appendonly", "yes", "--appendfsync", "always", "--maxmemory-policy", "noeviction")

        @DynamicPropertySource
        @JvmStatic
        fun endpoint(registry: DynamicPropertyRegistry) {
            registry.add("datasource.redis.master.host") { server.host }
            registry.add("datasource.redis.master.port") { server.firstMappedPort }
            registry.add("datasource.redis.replicas[0].host") { server.host }
            registry.add("datasource.redis.replicas[0].port") { server.firstMappedPort }
        }
    }

    @MockitoSpyBean
    private lateinit var permitBoundary: RedisCouponPermits

    private var campaignId = 0L

    @BeforeEach
    fun setup() {
        admission.close()
        cleanup.truncateAllTables()
        clearCouponTestState(redis)
        campaignId = preparation.prepare(bootstrapConfirmed = true).id
        preparation.openAfterQuiescence(confirmed = true)

        val actualId = server.execInContainer("redis-cli", "--raw", "INFO", "server").stdout.lineSequence()
            .first { it.startsWith("run_id:") }.substringAfter(':').trim()
        assertThat(redis.opsForValue().get(CouponPermitKeys.STATE)).isEqualTo(actualId)
    }

    @AfterEach
    fun clean() {
        ensureRunning()
        admission.close()
        clearCouponTestState(redis)
        cleanup.truncateAllTables()
    }

    /** application endpoint를 중단해 원본 조회 503·DB projection 보존·자동 재개 금지를 확인한다. */
    @Test
    fun `실제 Redis 장애는 원본 조회를 503으로 거절하고 커밋된 DB 쿠폰은 보존한다`() {
        val owner = users.create("기존 소유자").id
        val newcomer = users.create("신규 요청자").id
        val committed = coupons.issue(owner, campaignId).coupon
        assertThat(worker.runOnce()).isEqualTo(1)
        stopEndpoint()

        mvc.perform(post(path()).header("X-USER-ID", newcomer))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.meta.errorCode").value("COUPON_ADMISSION_UNAVAILABLE"))
        mvc.perform(get("/api/v1/coupons").header("X-USER-ID", owner))
            .andExpect(status().isServiceUnavailable)
        mvc.perform(post(path()).header("X-USER-ID", owner))
            .andExpect(status().isServiceUnavailable)

        assertThat(campaigns.find(campaignId)!!.issuedCount).isEqualTo(1)
        assertThat(issued.findByUser(owner)).containsExactly(committed)
        assertThat(issued.findByUser(newcomer)).isEmpty()
        assertThatThrownBy { admission.acquire() }.isInstanceOf(CouponAdmissionException::class.java)

        ensureRunning()
        mvc.perform(post(path()).header("X-USER-ID", newcomer)).andExpect(status().isServiceUnavailable)
        preparation.openAfterQuiescence(confirmed = true)

        mvc.perform(post(path()).header("X-USER-ID", newcomer)).andExpect(status().isCreated)
        assertThat(worker.runOnce()).isEqualTo(1)
        assertThat(campaigns.find(campaignId)!!.issuedCount).isEqualTo(2)
        assertThat(checkNotNull(redis.opsForSet().size(CouponPermitKeys.ACTIVE))).isZero()
        assertThat(redis.hasKey(CouponPermitKeys.STATE)).isTrue()
    }

    /** Redis 확정 뒤 실제 반환 endpoint를 중단하고, 쿠폰을 보존한 채 명시적 재개로 남은 비활성 점유만 정리한다. */
    @Test
    fun `실제 슬롯 반환 실패 뒤 Redis를 재시작하고 명시적으로 재개해도 쿠폰은 보존된다`() {
        val owner = users.create("소유자").id
        val newcomer = users.create("신규 요청자").id
        doAnswer { invocation ->
            stopEndpoint()
            invocation.callRealMethod()
        }.`when`(permitBoundary).release(any())

        mvc.perform(post(path()).header("X-USER-ID", owner)).andExpect(status().isCreated)

        assertThat(issued.findByUser(owner)).isEmpty()
        assertThat(campaigns.find(campaignId)).isNull()
        assertThatThrownBy { admission.acquire() }.isInstanceOf(CouponAdmissionException::class.java)
        mvc.perform(post(path()).header("X-USER-ID", owner)).andExpect(status().isServiceUnavailable)
        mvc.perform(post(path()).header("X-USER-ID", newcomer)).andExpect(status().isServiceUnavailable)

        doCallRealMethod().`when`(permitBoundary).release(any())
        ensureRunning()
        preparation.openAfterQuiescence(confirmed = true)

        assertThat(checkNotNull(redis.opsForSet().size(CouponPermitKeys.ACTIVE))).isZero()
        assertThat(redis.hasKey(CouponPermitKeys.STATE)).isTrue()
        mvc.perform(post(path()).header("X-USER-ID", newcomer)).andExpect(status().isCreated)
        assertThat(worker.runOnce()).isEqualTo(2)
        assertThat(campaigns.find(campaignId)!!.issuedCount).isEqualTo(2)
        assertThat(checkNotNull(redis.opsForSet().size(CouponPermitKeys.ACTIVE))).isZero()
    }

    private fun path() = "/api/v1/coupon-campaigns/$campaignId/issues"

    private fun stopEndpoint() {
        server.dockerClient.stopContainerCmd(server.containerId).exec()
    }

    /** 소유 컨테이너를 삭제하지 않고 재시작해 application이 사용하던 같은 endpoint의 복구를 관측한다. */
    private fun ensureRunning() {
        if (server.dockerClient.inspectContainerCmd(server.containerId).exec().state.running != true) {
            server.dockerClient.startContainerCmd(server.containerId).exec()
        }
        val actualPort = server.dockerClient.inspectContainerCmd(server.containerId).exec()
            .networkSettings.ports.bindings[ExposedPort.tcp(6379)]?.firstOrNull()?.hostPortSpec?.toInt()
        check(actualPort == server.firstMappedPort) {
            "Docker restart changed Redis endpoint: ${server.firstMappedPort} -> $actualPort"
        }

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            if (runCatching { redis.connectionFactory!!.connection.use { it.ping() } }.getOrNull() == "PONG") return
            Thread.sleep(20)
        }

        error("Application Redis endpoint did not reconnect")
    }
}
