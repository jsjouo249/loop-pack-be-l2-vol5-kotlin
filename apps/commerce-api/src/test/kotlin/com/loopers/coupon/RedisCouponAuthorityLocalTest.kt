package com.loopers.coupon

import com.fasterxml.jackson.databind.ObjectMapper
import com.loopers.application.coupon.CouponAdmissionService
import com.loopers.application.coupon.CouponApplicationService
import com.loopers.application.coupon.CouponCampaignPreparation
import com.loopers.domain.commerce.CommerceException
import com.loopers.domain.commerce.CommerceFailure
import com.loopers.domain.coupon.CouponCampaign
import com.loopers.domain.coupon.CouponAdmissionFailure
import com.loopers.domain.coupon.CouponAdmissionException
import com.loopers.domain.user.User
import com.loopers.domain.user.UserRepository
import com.loopers.infrastructure.coupon.CouponDataKeys
import com.loopers.infrastructure.coupon.CouponPermitKeys
import com.loopers.infrastructure.coupon.RedisCouponAuthority
import com.loopers.infrastructure.coupon.RedisCouponPermits
import io.lettuce.core.ReadFrom
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.data.redis.connection.RedisPassword
import org.springframework.data.redis.connection.RedisStaticMasterReplicaConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.RedisCallback
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.serializer.StringRedisSerializer
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 명시적 Gradle task에서만 설치된 Redis를 사용한다. 기존 endpoint 대신 테스트 소유 process/AOF를 준비한다. */
@Tag("local-redis")
class RedisCouponAuthorityLocalTest {
    private lateinit var directory: Path
    private lateinit var server: Process
    private lateinit var redis: RedisTemplate<String, String>
    private lateinit var authority: RedisCouponAuthority
    private lateinit var admission: CouponAdmissionService
    private lateinit var preparation: CouponCampaignPreparation
    private lateinit var application: CouponApplicationService
    private val factories = mutableListOf<LettuceConnectionFactory>()
    private val users = mock<UserRepository>()
    private var port = 0

    @BeforeEach
    fun setup() {
        directory = Files.createTempDirectory("coupon-redis-")
        port = ServerSocket(0).use { it.localPort }
        startServer()
        redis = template()
        awaitReady()
        authority = RedisCouponAuthority(redis, ObjectMapper())
        admission = CouponAdmissionService(RedisCouponPermits(redis), SimpleMeterRegistry(), 32)
        preparation = CouponCampaignPreparation(authority, admission)
        preparation.prepare(bootstrapConfirmed = true)
        preparation.openAfterQuiescence(true)
        whenever(users.find(any())).thenReturn(User("구매자"))
        application = CouponApplicationService(users, authority, admission)
    }

    @AfterEach
    fun clean() {
        factories.forEach { it.destroy() }
        if (::server.isInitialized && server.isAlive) {
            server.destroy()
            check(server.waitFor(5, TimeUnit.SECONDS))
        }
        if (::directory.isInitialized) {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    /** 실제 Spring Data MASTER 연결로 경쟁·문자열 직렬화·발급 결과·Stream pending/ACK를 함께 검증한다. */
    @Test
    fun `real adapters issue concurrently read immediately and replay Stream until acknowledgement`() {
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val pending = (1L..8L).map { user ->
                pool.submit(
                    Callable {
                    start.await()
                    application.issue(user, 1)
                },
                )
            }
            start.countDown()
            val results = pending.map { it.get(10, TimeUnit.SECONDS) }

            assertThat(results.map { it.coupon.id }.toSet()).hasSize(8)
            assertThat(results).allMatch { it.created }
            val first = results.first().coupon
            assertThat(application.issue(first.userId, 1).coupon).isEqualTo(first)
            assertThat(application.owned(first.userId)).containsExactly(first)
            assertThat(application.owned(Long.MAX_VALUE)).isEmpty()
            assertThat(checkNotNull(redis.opsForSet().size(CouponPermitKeys.ACTIVE))).isZero()

            val records = authority.readPendingOrNew(20)
            assertThat(records).hasSize(8)
            assertThat(authority.readPendingOrNew(20).map { it.recordId to it.event.coupon })
                .isEqualTo(records.map { it.recordId to it.event.coupon })
            records.forEach { authority.acknowledge(it.recordId) }
            assertThat(authority.readPendingOrNew(20)).isEmpty()
            assertThat(checkNotNull(redis.opsForStream<String, String>().size(CouponDataKeys.EVENTS))).isEqualTo(8)
        } finally {
            start.countDown()
            pool.shutdownNow()
            pool.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `real Lua rejects quota overflow and concurrent duplicate executions produce one fact`() {
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val permits = List(8) { admission.acquire() }
        try {
            val duplicates = permits.map { permit ->
                pool.submit(
                    Callable {
                    start.await()
                    try {
                        authority.issue(1, 1, permit)
                    } finally {
                        admission.complete(permit, true)
                    }
                },
                )
            }
            start.countDown()
            val results = duplicates.map { it.get(10, TimeUnit.SECONDS) }
            assertThat(results.count { it.created }).isEqualTo(1)
            assertThat(results.map { it.coupon.id }.toSet()).hasSize(1)
            assertThat(checkNotNull(redis.opsForStream<String, String>().size(CouponDataKeys.EVENTS))).isEqualTo(1)

            admission.close()
            preparation.prepare(CouponCampaign("다섯 장", "혜택", 5, id = 2))
            preparation.openAfterQuiescence(true)
            val pending = (1L..8L).map { user -> pool.submit(Callable { runCatching { application.issue(user, 2) } }) }
            val outcomes = pending.map { it.get(10, TimeUnit.SECONDS) }

            assertThat(outcomes.count { it.isSuccess }).isEqualTo(5)
            assertThat(outcomes.mapNotNull { it.exceptionOrNull() }).allMatch {
                it is CommerceException && it.reason == CommerceFailure.COUPON_SOLD_OUT
            }
            assertThat(redis.opsForHash<String, String>().get(CouponDataKeys.campaign(2), "issued")).isEqualTo("5")
            assertThat(checkNotNull(redis.opsForStream<String, String>().size(CouponDataKeys.EVENTS))).isEqualTo(6)
            assertThat(checkNotNull(redis.opsForSet().size(CouponPermitKeys.ACTIVE))).isZero()
        } finally {
            start.countDown()
            pool.shutdownNow()
            pool.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `actual denied XADD leaves dirty partial state blocks reads and cannot be repaired by reopening permits`() {
        redis.execute(
            RedisCallback { connection ->
            connection.execute(
                "ACL",
                *listOf("SETUSER", "coupon-fault", "on", ">test-password", "~*", "+@all", "-xadd")
                    .map { it.toByteArray() }.toTypedArray(),
            )
        },
        )
        val failingAuthority = RedisCouponAuthority(template("coupon-fault"), ObjectMapper())
        val failingApplication = CouponApplicationService(users, failingAuthority, admission)

        assertThatThrownBy { failingApplication.issue(1, 1) }.isInstanceOf(CouponAdmissionException::class.java)

        assertThat(redis.opsForHash<String, String>().get(CouponDataKeys.META, "health")).isEqualTo("DIRTY")
        assertThat(redis.opsForHash<String, String>().get(CouponDataKeys.campaign(1), "issued")).isEqualTo("1")
        assertThat(checkNotNull(redis.opsForStream<String, String>().size(CouponDataKeys.EVENTS))).isZero()
        assertThat(checkNotNull(redis.opsForSet().size(CouponPermitKeys.ACTIVE))).isEqualTo(1)
        assertThatThrownBy { application.owned(1) }.isInstanceOf(CouponAdmissionException::class.java)
        assertThatThrownBy { preparation.openAfterQuiescence(true) }.isInstanceOf(CouponAdmissionException::class.java)
        assertThat(redis.opsForHash<String, String>().get(CouponDataKeys.META, "health")).isEqualTo("DIRTY")
    }

    @Test
    fun `AOF restart preserves facts pending delivery and requires explicit admission reopening`() {
        val first = application.issue(1, 1).coupon
        val records = authority.readPendingOrNew(20)
        admission.close()
        server.destroy()
        check(server.waitFor(5, TimeUnit.SECONDS))
        startServer()
        awaitReady()

        assertThat(application.owned(1)).containsExactly(first)
        assertThat(application.issue(1, 1).coupon).isEqualTo(first)
        assertThat(authority.readPendingOrNew(20).map { it.recordId to it.event.coupon })
                .isEqualTo(records.map { it.recordId to it.event.coupon })
        assertThatThrownBy { application.issue(2, 1) }.isInstanceOf(CouponAdmissionException::class.java)
        preparation.prepare()
        preparation.openAfterQuiescence(true)
        assertThat(application.issue(2, 1).created).isTrue()
        assertThat(redis.opsForHash<String, String>().get(CouponDataKeys.campaign(1), "issued")).isEqualTo("2")
    }

    @Test
    fun `lost registered campaign and lost owner fail closed without resetting counters`() {
        application.issue(1, 1)
        admission.close()
        redis.delete(CouponDataKeys.owner(1))
        assertThatThrownBy { application.issue(1, 1) }.isInstanceOf(CouponAdmissionException::class.java)
        assertThat(redis.opsForHash<String, String>().get(CouponDataKeys.campaign(1), "issued")).isEqualTo("1")

        redis.delete(CouponDataKeys.campaign(1))
        assertThatThrownBy { preparation.prepare() }.isInstanceOf(CouponAdmissionException::class.java)
        assertThat(redis.hasKey(CouponDataKeys.campaign(1))).isFalse()
    }

    /** 실제 Redis의 세 token을 유지한 동안 새 발급은 대기하고, 반환 알림 뒤 같은 요청이 발급된다. */
    @Test
    fun `real Redis full slots wait and return notification admits the waiting issue`() {
        admission.close()
        val meters = SimpleMeterRegistry()
        val waitingAdmission = CouponAdmissionService(RedisCouponPermits(redis), meters, 3, 2_000, 1, 10_000)
        waitingAdmission.openAfterQuiescence(true)
        val waitingApplication = CouponApplicationService(users, authority, waitingAdmission)
        val held = List(3) { waitingAdmission.acquire() }
        val pool = Executors.newSingleThreadExecutor()
        try {
            val pending = pool.submit(Callable { waitingApplication.issue(99, 1) })
            awaitWaiting(meters)
            assertThat(pending.isDone).isFalse()
            assertThat(authority.find(99, 1)).isNull()
            assertThat(checkNotNull(redis.opsForSet().size(CouponPermitKeys.ACTIVE))).isEqualTo(3)

            waitingAdmission.complete(held[0], true)
            assertThat(pending.get(1, TimeUnit.SECONDS).created).isTrue()
            assertThat(authority.owned(99)).hasSize(1)
            assertThat(checkNotNull(redis.opsForSet().size(CouponPermitKeys.ACTIVE))).isEqualTo(2)
            assertThat(meters.get("coupon.admission.waiting").gauge().value()).isZero()
        } finally {
            held.forEach { waitingAdmission.complete(it, true) }
            waitingAdmission.close()
            pool.shutdownNow()
            pool.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `real Redis waiting limit and deadline never issue or remove held tokens`() {
        admission.close()
        val meters = SimpleMeterRegistry()
        val waitingAdmission = CouponAdmissionService(RedisCouponPermits(redis), meters, 3, 500, 1, 10_000)
        waitingAdmission.openAfterQuiescence(true)
        val waitingApplication = CouponApplicationService(users, authority, waitingAdmission)
        val held = List(3) { waitingAdmission.acquire() }
        val pool = Executors.newSingleThreadExecutor()
        try {
            val pending = pool.submit(Callable { runCatching { waitingApplication.issue(99, 1) }.exceptionOrNull() })
            awaitWaiting(meters)

            assertThatThrownBy { waitingApplication.issue(100, 1) }.isInstanceOfSatisfying(CouponAdmissionException::class.java) {
                assertThat(it.reason).isEqualTo(CouponAdmissionFailure.FULL)
            }
            assertThat(meters.counter("coupon.admission", "outcome", "waiting_full").count()).isEqualTo(1.0)
            assertThat(pending.get(2, TimeUnit.SECONDS)).isInstanceOfSatisfying(CouponAdmissionException::class.java) {
                assertThat(it.reason).isEqualTo(CouponAdmissionFailure.FULL)
            }
            assertThat(authority.owned(99)).isEmpty()
            assertThat(authority.owned(100)).isEmpty()
            assertThat(checkNotNull(redis.opsForSet().size(CouponPermitKeys.ACTIVE))).isEqualTo(3)
            assertThat(redis.opsForHash<String, String>().get(CouponDataKeys.campaign(1), "issued")).isEqualTo("0")
            assertThat(meters.get("coupon.admission.waiting").gauge().value()).isZero()
        } finally {
            held.forEach { waitingAdmission.complete(it, true) }
            waitingAdmission.close()
            pool.shutdownNow()
            pool.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    private fun awaitWaiting(meters: SimpleMeterRegistry) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (meters.get("coupon.admission.waiting").gauge().value() != 1.0 && System.nanoTime() < deadline) Thread.sleep(1)
        assertThat(meters.get("coupon.admission.waiting").gauge().value()).isEqualTo(1.0)
    }

    private fun startServer() {
        server = ProcessBuilder(
            "redis-server", "--bind", "127.0.0.1", "--port", port.toString(), "--save", "",
            "--appendonly", "yes", "--appendfsync", "always", "--maxmemory-policy", "noeviction",
        ).directory(directory.toFile()).redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(directory.resolve("redis.log").toFile())).start()
    }

    private fun awaitReady() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            if (runCatching { redis.connectionFactory!!.connection.use { it.ping() } }.getOrNull() == "PONG") return
            check(server.isAlive) { Files.readString(directory.resolve("redis.log")) }
            Thread.sleep(20)
        }
        error("Test-owned Redis did not start")
    }

    private fun template(username: String? = null): RedisTemplate<String, String> {
        val nodes = RedisStaticMasterReplicaConfiguration("127.0.0.1", port)
        if (username != null) {
            nodes.username = username
            nodes.password = RedisPassword.of("test-password")
        }
        val factory = LettuceConnectionFactory(
            nodes,
            LettuceClientConfiguration.builder().readFrom(ReadFrom.MASTER).commandTimeout(Duration.ofSeconds(1)).build(),
        )
        factory.afterPropertiesSet()
        factories.add(factory)

        return RedisTemplate<String, String>().apply {
            connectionFactory = factory
            keySerializer = StringRedisSerializer()
            valueSerializer = StringRedisSerializer()
            hashKeySerializer = StringRedisSerializer()
            hashValueSerializer = StringRedisSerializer()
            afterPropertiesSet()
        }
    }
}
