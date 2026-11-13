package com.loopers.infrastructure.coupon

import com.loopers.config.redis.RedisConfig
import com.loopers.domain.coupon.CouponAdmissionException
import com.loopers.domain.coupon.CouponAdmissionFailure
import com.loopers.domain.coupon.CouponPermit
import com.loopers.domain.coupon.CouponPermits
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.redis.connection.RedisConnection
import org.springframework.data.redis.connection.ReturnType
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Component

@Component
class RedisCouponPermits(
    @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) private val redis: RedisTemplate<String, String>,
) : CouponPermits {
    private val access = CouponRedisAccess(redis)
    private val acquireScript = CouponRedisAccess.resource("acquire")
    private val releaseScript = CouponRedisAccess.resource("release")
    private val initializeScript = CouponRedisAccess.resource("initialize")

    override fun initializeAfterQuiescence(): String = access.onPrimary { connection, identity ->
        if (evaluate(connection, initializeScript, identity) != 1L) throw unavailable()

        identity
    }

    override fun acquire(permit: CouponPermit, capacity: Int): Boolean {
        require(capacity > 0)
        val result = access.onPrimary(permit.primaryId) { connection, _ ->
            evaluate(
                connection,
                acquireScript,
                permit.primaryId,
                permit.token,
                capacity.toString(),
            )
        }

        return when (result) {
            1L -> true
            0L -> false
            else -> throw unavailable()
        }
    }

    override fun release(permit: CouponPermit) {
        val result = access.onPrimary(permit.primaryId) { connection, _ ->
            evaluate(connection, releaseScript, permit.primaryId, permit.token)
        }

        if (result !in 0L..1L) throw unavailable()
    }

    private fun evaluate(connection: RedisConnection, script: ByteArray, vararg arguments: String): Long =
        connection.scriptingCommands().eval<Long>(
            script,
            ReturnType.INTEGER,
            2,
            CouponPermitKeys.ACTIVE.toByteArray(Charsets.UTF_8),
            CouponPermitKeys.STATE.toByteArray(Charsets.UTF_8),
            *arguments.map { it.toByteArray(Charsets.UTF_8) }.toTypedArray(),
        ) ?: throw unavailable()

    private fun unavailable(cause: Throwable? = null) = CouponAdmissionException(CouponAdmissionFailure.UNAVAILABLE, cause)
}
