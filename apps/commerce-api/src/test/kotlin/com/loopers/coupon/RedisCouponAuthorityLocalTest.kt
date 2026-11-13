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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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
    fun `실제 Redis 동시 발급 결과는 즉시 조회되고 Stream은 ACK 전까지 재전달된다`() {
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

    /** 동시 중복 발급은 한 사실로 수렴하는지, 별도 캠페인의 여덟 요청은 한도 다섯 장까지만 성공하는지 확인한다. */
    @Test
    fun `실제 Lua는 동시 중복 발급을 한 번만 기록하고 발급 한도 초과를 거절한다`() {
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

    /** XADD만 금지한 ACL로 선행 쓰기 후 오류를 발생시켜 부분 변경·DIRTY를 확인하고 조회와 재개 거절을 검증한다. */
    @Test
    fun `실제 XADD 권한 오류는 DIRTY와 부분 변경을 남기고 조회 및 슬롯 재개를 차단한다`() {
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

    /** 같은 AOF를 정상 종료·재시작해 발급 사실과 pending을 대조한다. replica 승격이나 HA failover를 검증하지 않는다. */
    @Test
    fun `AOF 정상 재시작은 발급 사실과 pending을 보존하고 신규 발급은 명시적 재개를 요구한다`() {
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
    fun `등록된 캠페인과 보유 키가 유실되면 발급 수를 초기화하지 않고 요청을 차단한다`() {
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
    fun `실제 Redis 슬롯이 가득 차면 발급을 대기하고 반환 알림 뒤 같은 요청을 처리한다`() {
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

    /** 세 슬롯과 한 대기자를 유지해 초과 등록·대기 만료를 각각 확인하고 쿠폰 미발급·기존 점유 보존을 검증한다. */
    @Test
    fun `실제 Redis 대기 상한과 시간 초과는 발급하거나 점유 중인 슬롯을 제거하지 않는다`() {
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

    /** 기존 발급과 유효 permit을 준비한 뒤 키 타입 하나만 훼손해 오류 전후 원본·Stream·점유 전체를 대조한다. */
    @ParameterizedTest(name = "{displayName} [{0}]")
    @ValueSource(strings = ["meta", "campaign", "owner", "events", "permits", "state"])
    fun `원본과 permit 키의 타입이 잘못되면 Lua는 기존 결과도 반환하지 않고 데이터를 보존한다`(keyName: String) {
        application.issue(1, 1)
        val permit = admission.acquire()
        val key = checkNotNull(authorityKeys()[keyName])
        redis.delete(key)
        if (keyName == "state") {
            redis.opsForHash<String, String>().put(key, "invalid", "type")
        } else {
            redis.opsForValue().set(key, "wrong-type")
        }
        val before = authoritySnapshot()

        assertThatThrownBy { authority.issue(1, 1, permit) }.isInstanceOf(CouponAdmissionException::class.java)

        assertThat(authoritySnapshot()).isEqualTo(before)
    }

    /** 모든 대상 키가 존재하도록 발급·획득한 뒤 TTL 하나를 설정하고 거절 시 발급 사실과 점유가 그대로인지 확인한다. */
    @ParameterizedTest(name = "{displayName} [{0}]")
    @ValueSource(strings = ["meta", "campaign", "owner", "events", "permits", "state"])
    fun `원본과 permit 키에 TTL이 있으면 Lua는 발급을 거절하고 데이터를 보존한다`(keyName: String) {
        application.issue(1, 1)
        val permit = admission.acquire()
        check(redis.expire(checkNotNull(authorityKeys()[keyName]), Duration.ofMinutes(1)) == true)
        val before = authoritySnapshot()

        assertThatThrownBy { authority.issue(1, 1, permit) }.isInstanceOf(CouponAdmissionException::class.java)

        assertThat(authoritySnapshot()).isEqualTo(before)
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @ValueSource(strings = ["-1", "1.5", "not-a-number", "9007199254740990", "9007199254740991"])
    fun `sequence가 잘못됐거나 다음 ID를 안전하게 만들 수 없으면 발급 사실을 기록하지 않는다`(sequence: String) {
        val permit = admission.acquire()
        redis.opsForHash<String, String>().put(CouponDataKeys.META, "sequence", sequence)
        val before = authoritySnapshot()

        assertThatThrownBy { authority.issue(1, 1, permit) }.isInstanceOf(CouponAdmissionException::class.java)

        assertThat(authoritySnapshot()).isEqualTo(before)
    }

    /** 정상 발급 후 복구에 필요한 Stream 증거만 훼손해 명시적 재개가 permit 정리보다 먼저 거절되는지 확인한다. */
    @ParameterizedTest(name = "{displayName} [{0}]")
    @ValueSource(strings = ["Stream 유실", "Stream 길이 불일치", "consumer group 유실"])
    fun `Stream과 sequence 또는 consumer group이 일치하지 않으면 재개하지 않고 데이터를 보존한다`(failure: String) {
        application.issue(1, 1)
        admission.close()
        when (failure) {
            "Stream 유실" -> redis.delete(CouponDataKeys.EVENTS)
            "Stream 길이 불일치" -> redis.opsForHash<String, String>().put(CouponDataKeys.META, "sequence", "2")
            "consumer group 유실" -> redis.opsForStream<String, String>().destroyGroup(CouponDataKeys.EVENTS, CouponDataKeys.GROUP)
            else -> error("Unknown recovery failure: $failure")
        }
        val before = authoritySnapshot()

        assertThatThrownBy { preparation.openAfterQuiescence(true) }.isInstanceOf(CouponAdmissionException::class.java)
        assertThatThrownBy { admission.acquire() }.isInstanceOf(CouponAdmissionException::class.java)

        assertThat(authoritySnapshot()).isEqualTo(before)
    }

    private fun authorityKeys() = mapOf(
        "meta" to CouponDataKeys.META,
        "campaign" to CouponDataKeys.campaign(1),
        "owner" to CouponDataKeys.owner(1),
        "events" to CouponDataKeys.EVENTS,
        "permits" to CouponPermitKeys.ACTIVE,
        "state" to CouponPermitKeys.STATE,
    )

    private fun authoritySnapshot() = authorityKeys().mapValues { (_, key) -> redis.dump(key)?.toList() }

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
