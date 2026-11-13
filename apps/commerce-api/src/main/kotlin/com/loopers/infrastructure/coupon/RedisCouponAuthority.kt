package com.loopers.infrastructure.coupon

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.loopers.config.redis.RedisConfig
import com.loopers.domain.commerce.CommerceException
import com.loopers.domain.commerce.CommerceFailure
import com.loopers.domain.coupon.CouponAuthority
import com.loopers.domain.coupon.CouponCampaign
import com.loopers.domain.coupon.CouponDecision
import com.loopers.domain.coupon.CouponEventRecord
import com.loopers.domain.coupon.CouponIssuedEvent
import com.loopers.domain.coupon.CouponIssueLog
import com.loopers.domain.coupon.CouponPermit
import com.loopers.domain.coupon.IssuedCoupon
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.temporal.ChronoUnit

@Component
class RedisCouponAuthority(
    @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) redis: RedisTemplate<String, String>,
    private val mapper: ObjectMapper,
) : CouponAuthority, CouponIssueLog {
    private val access = CouponRedisAccess(redis)
    private val common = CouponRedisAccess.resource("ssot-common")
    private val prepareScript = script("prepare")
    private val readScript = script("read")
    private val issueScript = script("issue")
    private val eventsScript = script("events")
    private val ackScript = script("ack")

    override fun prepare(campaign: CouponCampaign, bootstrapConfirmed: Boolean) {
        require(campaign.id > 0 && campaign.issuedCount == 0L)
        verifyPersistence()
        execute(
            prepareScript,
            listOf(CouponDataKeys.META, CouponDataKeys.campaign(campaign.id), CouponDataKeys.EVENTS),
            campaign.id.toString(), campaign.name, campaign.benefitDescription, campaign.issuanceLimit.toString(),
            if (campaign.issuanceEnabled) "1" else "0", if (bootstrapConfirmed) "1" else "0", CouponDataKeys.GROUP,
        )
    }

    override fun verifyReady() {
        verifyPersistence()
        execute(readScript, listOf(CouponDataKeys.META, CouponDataKeys.EVENTS), "ready", CouponDataKeys.GROUP)
    }

    override fun find(userId: Long, campaignId: Long): IssuedCoupon? = execute(
        readScript,
        listOf(CouponDataKeys.META, CouponDataKeys.owner(userId)),
        "find",
        campaignId.toString(),
        userId.toString(),
    ).takeUnless { it.isNull }?.let { coupon(it, userId, campaignId) }

    override fun owned(userId: Long): List<IssuedCoupon> {
        val records =
            execute(readScript, listOf(CouponDataKeys.META, CouponDataKeys.owner(userId)), "owned", "", userId.toString())
        check(records.isArray)

        return records.map { coupon(it, expectedUser = userId) }
            .sortedWith(compareByDescending<IssuedCoupon> { it.issuedAt }.thenByDescending { it.id })
    }

    /** 소유 permit으로 발급 Lua를 실행하고 확정 업무 거절만 도메인 오류로 구분한다. */
    override fun issue(userId: Long, campaignId: Long, permit: CouponPermit): CouponDecision {
        val result = execute(
            issueScript,
            listOf(
                CouponDataKeys.META,
                CouponDataKeys.campaign(campaignId),
                CouponDataKeys.owner(userId),
                CouponDataKeys.EVENTS,
                CouponPermitKeys.ACTIVE,
                CouponPermitKeys.STATE,
            ),
            userId.toString(),
            campaignId.toString(),
            Instant.now().truncatedTo(ChronoUnit.MICROS).toString(),
            permit.token,
            permit.primaryId,
            expected = permit.primaryId,
        )
        when (result.path("status").asText()) {
            "MISSING" -> throw CommerceException(CommerceFailure.COUPON_CAMPAIGN_NOT_FOUND)
            "INACTIVE" -> throw CommerceException(CommerceFailure.COUPON_CAMPAIGN_INACTIVE)
            "SOLD_OUT" -> throw CommerceException(CommerceFailure.COUPON_SOLD_OUT)
            "CREATED", "EXISTING" -> return CouponDecision(
                coupon(result.path("coupon"), userId, campaignId),
                result.path("status").asText() == "CREATED",
            )
            else -> throw CouponRedisAccess.unavailable()
        }
    }

    /** 고정 consumer의 pending/새 이벤트를 읽고 문자열로 보존한 발급 사실을 복원한다. */
    override fun readPendingOrNew(limit: Int): List<CouponEventRecord> {
        require(limit in 1..100)
        val records = execute(
            eventsScript,
            listOf(CouponDataKeys.META, CouponDataKeys.EVENTS),
            limit.toString(),
            CouponDataKeys.GROUP,
            CouponDataKeys.CONSUMER,
        )
        check(records.isArray)

        return records.map { record ->
            val fact = record.path("fact")
            val coupon = coupon(fact)
            val campaign = CouponCampaign(
                fact.required("campaignName").asText(),
                coupon.benefitDescription,
                fact.required("issuanceLimit").asText().toLong(),
                coupon.campaignId,
            )
            CouponEventRecord(record.required("recordId").asText(), CouponIssuedEvent(campaign, coupon))
        }
    }

    override fun acknowledge(recordId: String) {
        execute(ackScript, listOf(CouponDataKeys.META, CouponDataKeys.EVENTS), CouponDataKeys.GROUP, recordId)
    }

    /** 같은 primary의 결과만 사용하고 JSON·상태 오류도 발급 불가로 처리한다. */
    private fun execute(script: ByteArray, keys: List<String>, vararg args: String, expected: String? = null): JsonNode =
        access.onPrimary(expected) { connection, _ -> mapper.readTree(access.text(connection, script, keys, *args)) }

    private fun coupon(value: JsonNode, expectedUser: Long? = null, expectedCampaign: Long? = null): IssuedCoupon = try {
        val coupon = IssuedCoupon(
            value.required("campaignId").asText().toLong(),
            value.required("userId").asText().toLong(),
            value.required("benefitDescription").asText(),
            Instant.parse(value.required("issuedAt").asText()),
            value.required("id").asText().toLong(),
        )
        check(coupon.id > 0 && (expectedUser == null || coupon.userId == expectedUser))
        check(expectedCampaign == null || coupon.campaignId == expectedCampaign)

        coupon
    } catch (failure: Exception) {
        throw CouponRedisAccess.unavailable(failure)
    }

    private fun verifyPersistence() {
        access.onPrimary { connection, _ ->
            val server = connection.serverCommands()
            check(server.getConfig("appendonly")?.getProperty("appendonly") == "yes") { "Coupon SSOT requires appendonly yes" }
            check(
                server.getConfig("appendfsync")?.getProperty("appendfsync") == "always",
            ) { "Coupon SSOT requires appendfsync always" }
            check(server.getConfig("maxmemory-policy")?.getProperty("maxmemory-policy") == "noeviction") {
                "Coupon SSOT requires maxmemory-policy noeviction"
            }
            true
        }
    }

    private fun script(name: String): ByteArray = common + "\n".toByteArray() + CouponRedisAccess.resource(name)
}
