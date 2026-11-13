package com.loopers.coupon

import com.loopers.domain.coupon.CouponAdmissionException
import com.loopers.domain.coupon.CouponPermit
import com.loopers.infrastructure.coupon.RedisCouponPermits
import com.loopers.infrastructure.coupon.CouponPermitKeys
import io.lettuce.core.ReadFrom
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.RedisStaticMasterReplicaConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.serializer.StringRedisSerializer
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CouponRedisNode : GenericContainer<CouponRedisNode>(DockerImageName.parse("redis:7.2"))

/** 독립 MASTER 클라이언트와 실제 primary·replica로 permit Lua의 원자성·소유권·서버 식별을 검증한다. Docker가 필요하다. */
@Testcontainers
class RedisCouponPermitsIntegrationTest {
    companion object {
        private val network = Network.newNetwork()

        @Container
        @JvmStatic
        val primary = CouponRedisNode().withNetwork(network).withNetworkAliases("coupon-primary").withExposedPorts(6379)

        @Container
        @JvmStatic
        val replica = CouponRedisNode().withNetwork(network).withExposedPorts(6379)
            .withCommand("redis-server", "--replicaof", "coupon-primary", "6379")

        @AfterAll
        @JvmStatic
        fun closeNetwork() = network.close()
    }

    private val factories = mutableListOf<LettuceConnectionFactory>()
    private lateinit var redis: RedisTemplate<String, String>
    private lateinit var first: RedisCouponPermits
    private lateinit var second: RedisCouponPermits
    private lateinit var primaryId: String

    @BeforeEach
    fun setup() {
        redis = template(ReadFrom.MASTER)
        first = RedisCouponPermits(redis)
        second = RedisCouponPermits(template(ReadFrom.MASTER))
        primaryId = first.initializeAfterQuiescence()
    }

    @AfterEach
    fun clean() {
        redis.delete(listOf(CouponPermitKeys.ACTIVE, CouponPermitKeys.STATE))
        factories.forEach { it.destroy() }
    }

    /** 두 클라이언트가 획득한 세 실행을 latch로 유지해 추가 획득 거절·TTL 없음·다른 token 반환의 무효를 확인한다. */
    @Test
    fun `독립 클라이언트의 세 실행이 점유하면 네 번째 획득은 거절되고 자기 슬롯만 반환한다`() {
        val held = CountDownLatch(3)
        val finish = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(3)
        try {
            val tasks = (0..2).map { index ->
                pool.submit(
                    Callable {
                        val client = if (index % 2 == 0) first else second
                        val permit = permit()
                        check(client.acquire(permit, 3))
                        held.countDown()
                        check(finish.await(5, TimeUnit.SECONDS))

                        client.release(permit)
                    },
                )
            }
            check(held.await(5, TimeUnit.SECONDS))

            assertThat(second.acquire(permit(), 3)).isFalse()
            assertThat(checkNotNull(redis.opsForSet().size(CouponPermitKeys.ACTIVE))).isEqualTo(3)
            assertThat(redis.getExpire(CouponPermitKeys.ACTIVE)).isEqualTo(-1)
            first.release(permit())
            assertThat(checkNotNull(redis.opsForSet().size(CouponPermitKeys.ACTIVE))).isEqualTo(3)

            finish.countDown()
            tasks.forEach { it.get(5, TimeUnit.SECONDS) }
            assertThat(redis.opsForSet().members(CouponPermitKeys.ACTIVE)).isEmpty()
        } finally {
            finish.countDown()
            pool.shutdownNow()
        }
    }

    /** 60개 획득을 함께 시작하고 성공한 token을 유지한 채 세 개만 수용됐는지 확인한 뒤 반복 반환의 멱등성을 검증한다. */
    @Test
    fun `동시 획득이 경쟁해도 허용한 세 슬롯을 초과하지 않는다`() {
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(16)
        try {
            val futures = (1..60).map { index ->
                pool.submit(
                    Callable {
                        start.await()
                        val client = if (index % 2 == 0) first else second
                        val permit = permit()

                        if (client.acquire(permit, 3)) permit else null
                    },
                )
            }
            start.countDown()
            val accepted = futures.mapNotNull { it.get(10, TimeUnit.SECONDS) }

            assertThat(accepted).hasSize(3)
            assertThat(checkNotNull(redis.opsForSet().size(CouponPermitKeys.ACTIVE))).isEqualTo(3)
            accepted.forEach(first::release)
            accepted.forEach(second::release)
            assertThat(redis.opsForSet().members(CouponPermitKeys.ACTIVE)).isEmpty()
        } finally {
            pool.shutdownNow()
        }
    }

    /** 같은 token 재확인 뒤 상태·점유 키를 순서대로 훼손해 잘못된 원본을 새 빈 상태로 받아들이지 않는지 확인한다. */
    @Test
    fun `같은 실행의 재확인은 멱등이고 상태 유실과 잘못된 타입 및 TTL 설정은 획득을 차단한다`() {
        val permit = permit()
        assertThat(first.acquire(permit, 3)).isTrue()
        assertThat(second.acquire(permit, 3)).isTrue()
        assertThat(checkNotNull(redis.opsForSet().size(CouponPermitKeys.ACTIVE))).isEqualTo(1)

        redis.delete(CouponPermitKeys.STATE)
        assertThatThrownBy { second.acquire(permit(), 3) }.isInstanceOf(CouponAdmissionException::class.java)
        assertThat(redis.opsForSet().members(CouponPermitKeys.ACTIVE)).containsExactly(permit.token)

        redis.delete(CouponPermitKeys.ACTIVE)
        assertThatThrownBy { first.acquire(permit(), 3) }.isInstanceOf(CouponAdmissionException::class.java)
        assertThat(redis.hasKey(CouponPermitKeys.ACTIVE)).isFalse()

        redis.opsForValue().set(CouponPermitKeys.ACTIVE, "wrong-type")
        assertThatThrownBy { first.acquire(permit(), 3) }.isInstanceOf(CouponAdmissionException::class.java)
        assertThat(redis.opsForValue().get(CouponPermitKeys.ACTIVE)).isEqualTo("wrong-type")

        redis.delete(listOf(CouponPermitKeys.ACTIVE, CouponPermitKeys.STATE))
        first.initializeAfterQuiescence()
        redis.expire(CouponPermitKeys.STATE, Duration.ofMinutes(1))
        assertThatThrownBy { first.acquire(permit(), 3) }.isInstanceOf(CouponAdmissionException::class.java)
        assertThat(redis.opsForSet().members(CouponPermitKeys.ACTIVE)).isEmpty()
    }

    @Test
    fun `replica 직접 연결과 확인되지 않은 primary 식별자로는 슬롯을 획득하지 못한다`() {
        val preferred = template(ReadFrom.REPLICA_PREFERRED)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (preferred.opsForValue().get(CouponPermitKeys.STATE) != primaryId && System.nanoTime() < deadline) {
            Thread.sleep(20)
        }
        assertThat(preferred.opsForValue().get(CouponPermitKeys.STATE)).isEqualTo(primaryId)

        val replicaOnly = createTemplate(RedisStaticMasterReplicaConfiguration(replica.host, replica.firstMappedPort), ReadFrom.MASTER)
        assertThatThrownBy { RedisCouponPermits(replicaOnly).acquire(permit(), 3) }.isInstanceOf(CouponAdmissionException::class.java)
        assertThatThrownBy { first.acquire(CouponPermit("another-token", "untrusted"), 3) }
            .isInstanceOf(CouponAdmissionException::class.java)
        assertThat(redis.opsForSet().members(CouponPermitKeys.ACTIVE)).isEmpty()
    }

    @Test
    fun `Redis 중단 뒤 획득을 거절하고 재시작한 서버도 자동으로 신뢰하지 않는다`() {
        CouponRedisNode().withExposedPorts(6379).use { server ->
            server.start()
            val oldTemplate = singleTemplate(server)
            val oldClient = RedisCouponPermits(oldTemplate)
            val oldId = oldClient.initializeAfterQuiescence()
            server.stop()

            assertThatThrownBy { oldClient.acquire(CouponPermit("old-execution", oldId), 3) }
                .isInstanceOf(CouponAdmissionException::class.java)

            server.start()
            val reconnected = RedisCouponPermits(singleTemplate(server))
            assertThatThrownBy { reconnected.acquire(CouponPermit("new-execution", oldId), 3) }
                .isInstanceOf(CouponAdmissionException::class.java)
        }
    }

    private fun permit() = CouponPermit(UUID.randomUUID().toString(), primaryId)

    private fun template(readFrom: ReadFrom): RedisTemplate<String, String> {
        val nodes = RedisStaticMasterReplicaConfiguration(primary.host, primary.firstMappedPort)
        nodes.addNode(replica.host, replica.firstMappedPort)

        return createTemplate(nodes, readFrom)
    }

    private fun singleTemplate(server: CouponRedisNode): RedisTemplate<String, String> =
        createTemplate(RedisStaticMasterReplicaConfiguration(server.host, server.firstMappedPort), ReadFrom.MASTER)

    private fun createTemplate(nodes: RedisStaticMasterReplicaConfiguration, readFrom: ReadFrom): RedisTemplate<String, String> {
        val factory = LettuceConnectionFactory(
            nodes,
            LettuceClientConfiguration.builder().readFrom(readFrom).commandTimeout(Duration.ofMillis(500)).build(),
        )
        factory.afterPropertiesSet()
        factories.add(factory)

        return RedisTemplate<String, String>().apply {
            connectionFactory = factory
            keySerializer = StringRedisSerializer()
            valueSerializer = StringRedisSerializer()
            afterPropertiesSet()
        }
    }
}
