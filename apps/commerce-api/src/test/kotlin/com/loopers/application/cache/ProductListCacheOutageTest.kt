package com.loopers.application.cache

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.loopers.application.transaction.CapturedSql
import com.loopers.application.transaction.CommerceTransactionTestSupport
import com.loopers.domain.product.ProductListCache
import com.loopers.infrastructure.product.RedisProductListCache
import com.github.dockerjava.api.model.ExposedPort
import com.github.dockerjava.api.model.PortBinding
import com.github.dockerjava.api.model.Ports
import java.net.ServerSocket
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import io.lettuce.core.ClientOptions
import io.lettuce.core.ReadFrom
import io.lettuce.core.SocketOptions
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.clearInvocations
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.connection.RedisStaticMasterReplicaConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.serializer.StringRedisSerializer
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.DockerClientFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

/** Stops only a newly created, test-owned Redis container; does not stop the shared test fixture. */
class ProductListCacheOutageTest : CommerceTransactionTestSupport() {
    @MockitoSpyBean private lateinit var cache: ProductListCache

    @Autowired private lateinit var mapper: ObjectMapper

    private fun browse(): JsonNode {
        val response = mvc.perform(get("/api/v1/products?sort=price_asc")).andExpect(status().isOk).andReturn().response
        return mapper.readTree(response.contentAsString)["data"]
    }

    @Test
    fun `real stopped restarted and unresponsive Redis falls back and recovers from persisted old snapshot`() {
        val id = product(100, 3)
        val hostPort = ServerSocket(0).use { it.localPort }
        DedicatedRedis().withExposedPorts(6379).withCreateContainerCmdModifier { command ->
            command.hostConfig!!.withPortBindings(PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", hostPort), ExposedPort.tcp(6379)))
        }.withCommand("redis-server", "--appendonly", "yes").use { server ->
            server.start()
            val factory = LettuceConnectionFactory(
                RedisStaticMasterReplicaConfiguration(server.host, server.firstMappedPort),
                LettuceClientConfiguration.builder().readFrom(ReadFrom.MASTER).commandTimeout(Duration.ofMillis(200))
                    .clientOptions(ClientOptions.builder().socketOptions(SocketOptions.builder().connectTimeout(Duration.ofMillis(200)).build()).build())
                    .build(),
            )
            factory.afterPropertiesSet()
            factory.start()
            try {
                val redis = RedisTemplate<String, String>().apply {
                    connectionFactory = factory
                    keySerializer = StringRedisSerializer()
                    valueSerializer = StringRedisSerializer()
                    afterPropertiesSet()
                }
                val meters = SimpleMeterRegistry()
                val dedicated = RedisProductListCache(redis, mapper, meters, Duration.ofSeconds(30))
                doAnswer { dedicated.read(it.getArgument(0)) }.whenever(cache).read(any())
                doAnswer { dedicated.put(it.getArgument(0), it.getArgument(1)) }.whenever(cache).put(any(), any())
                doAnswer { dedicated.evictAll() }.whenever(cache).evictAll()
                val old = browse()
                assertThat(browse()).isEqualTo(old)
                val docker = DockerClientFactory.instance().client()
                docker.stopContainerCmd(server.containerId).withTimeout(1).exec()
                clearInvocations(cache)
                CapturedSql.statements.clear()
                val start = System.nanoTime()
                assertThat(browse()).isEqualTo(old)
                val stoppedMs = (System.nanoTime() - start) / 1_000_000.0
                assertThat(CapturedSql.statements.filter { it.startsWith("select ") }).hasSize(3)
                verify(cache, never()).put(any(), any())
                assertThat(meters.counter("commerce.product.list.cache", "outcome", "read_failure").count()).isGreaterThanOrEqualTo(1.0)
                products.update(id, "중단 중 변경", 200)
                assertThat(number("select price from products where id=?", id)).isEqualTo(200)
                assertThat(meters.counter("commerce.product.list.cache", "outcome", "evict_failure").count()).isEqualTo(1.0)
                docker.startContainerCmd(server.containerId).exec()
                val key = "products:v1:list:brand=all:sort=price_asc:page=0:size=20"
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                var reconnected = false
                while (System.nanoTime() < deadline) {
                    try {
                        reconnected = redis.hasKey(key)
                        if (reconnected) break
                    } catch (_: Exception) { /* bounded reconnect */ }
                    Thread.sleep(50)
                }
                assertThat(reconnected).withFailMessage("Redis restart did not retain snapshot; logs=%s", server.logs).isTrue()
                // Restart retained the old snapshot; connection success alone is not recovery.
                assertThat(browse()).isEqualTo(old)
                redis.expire(key, Duration.ofMillis(200))
                val expiry = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (redis.hasKey(key) == true && System.nanoTime() < expiry) Thread.sleep(10)
                assertThat(redis.hasKey(key)).isFalse()
                val current = browse()
                assertThat(current["content"][0]["price"].asLong()).isEqualTo(200)
                assertThat(browse()).isEqualTo(current)
                // A connected but unresponsive server proves command timeout, separate from connect refusal.
                redis.execute(
                    org.springframework.data.redis.core.RedisCallback { connection ->
                    connection.execute("CLIENT", "PAUSE".toByteArray(), "1000".toByteArray(), "ALL".toByteArray())
                },
                )
                clearInvocations(cache)
                val pauseStart = System.nanoTime()
                assertThat(browse()).isEqualTo(current)
                val pausedMs = (System.nanoTime() - pauseStart) / 1_000_000.0
                verify(cache, never()).put(any(), any())
                assertThat(pausedMs).isGreaterThanOrEqualTo(150.0).isLessThan(3000.0)
                Thread.sleep(1100)
                dedicated.evictAll()
                assertThat(browse()).isEqualTo(current)
                assertThat(browse()).isEqualTo(current)
                val output = generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.exists(it.resolve("docs/week4")) }
                    .resolve("docs/week4/measurements/redis-outage.json")
                Files.createDirectories(output.parent)
                mapper.writerWithDefaultPrettyPrinter().writeValue(
                    output.toFile(),
                    mapOf(
                    "redisImage" to "redis:7.2",
                        "stoppedHttpMs" to stoppedMs,
                        "pausedHttpMs" to pausedMs,
                    "readFailures" to meters.counter("commerce.product.list.cache", "outcome", "read_failure").count(),
                    "evictFailures" to meters.counter("commerce.product.list.cache", "outcome", "evict_failure").count(),
                    "persistedOldSnapshotObserved" to true,
                        "expiredRefillAndHitEqualCurrent" to true,
                ),
                )
            } finally {
                factory.destroy()
            }
        }
    }
}

private class DedicatedRedis : GenericContainer<DedicatedRedis>(DockerImageName.parse("redis:7.2"))
