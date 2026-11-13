package com.loopers.infrastructure.coupon

import com.loopers.domain.coupon.CouponAdmissionException
import com.loopers.domain.coupon.CouponAdmissionFailure
import org.springframework.core.io.ClassPathResource
import org.springframework.data.redis.connection.RedisConnection
import org.springframework.data.redis.connection.ReturnType
import org.springframework.data.redis.core.RedisCallback
import org.springframework.data.redis.core.RedisTemplate

/** 두 쿠폰 어댑터의 MASTER 연결·문자열 직렬화·정체성 검증 경계를 공유한다. */
class CouponRedisAccess(private val redis: RedisTemplate<String, String>) {
    /** 작업 전후 같은 primary인지 확인하고 연결·역할·실행 결과 불명은 수용 불가로 변환한다. */
    fun <T : Any> onPrimary(expected: String? = null, action: (RedisConnection, String) -> T): T = try {
        redis.execute(
            RedisCallback { connection ->
                val identity = identity(connection)
                if (expected != null && expected != identity) throw unavailable()

                val result = action(connection, identity)
                if (identity(connection) != identity) throw unavailable()

                result
            },
        ) ?: throw unavailable()
    } catch (failure: CouponAdmissionException) {
        throw failure
    } catch (failure: Exception) {
        throw unavailable(failure)
    }

    fun text(connection: RedisConnection, script: ByteArray, keys: List<String>, vararg arguments: String): String =
        connection.scriptingCommands().eval<ByteArray>(
            script,
            ReturnType.VALUE,
            keys.size,
            *(keys + arguments).map { it.toByteArray(Charsets.UTF_8) }.toTypedArray(),
        )?.toString(Charsets.UTF_8) ?: throw unavailable()

    private fun identity(connection: RedisConnection): String {
        if (connection.serverCommands().info("replication")?.getProperty("role") != "master") throw unavailable()

        return connection.serverCommands().info("server")?.getProperty("run_id")?.takeIf { it.isNotBlank() }
            ?: throw unavailable()
    }

    companion object {
        fun resource(name: String): ByteArray = ClassPathResource("coupon/$name.lua").inputStream.use { it.readBytes() }

        fun unavailable(cause: Throwable? = null) = CouponAdmissionException(CouponAdmissionFailure.UNAVAILABLE, cause)
    }
}
