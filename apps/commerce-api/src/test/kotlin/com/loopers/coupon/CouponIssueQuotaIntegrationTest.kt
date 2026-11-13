package com.loopers.coupon

import com.loopers.application.coupon.CouponAdmissionService
import com.loopers.application.coupon.CouponApplicationService
import com.loopers.application.coupon.CouponCampaignPreparation
import com.loopers.application.coupon.CouponProjectionWorker
import com.loopers.application.user.UserInitializationService
import com.loopers.config.redis.RedisConfig
import com.loopers.domain.commerce.CommerceException
import com.loopers.domain.coupon.CouponCampaign
import com.loopers.domain.coupon.CouponCampaignRepository
import com.loopers.infrastructure.coupon.CouponDataKeys
import com.loopers.infrastructure.coupon.CouponPermitKeys
import com.loopers.utils.DatabaseCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest(properties = ["coupon.admission.capacity=32", "coupon.projection.enabled=false"])
class CouponIssueQuotaIntegrationTest @Autowired constructor(
    private val admission: CouponAdmissionService,
    private val preparation: CouponCampaignPreparation,
    private val coupons: CouponApplicationService,
    private val worker: CouponProjectionWorker,
    private val campaigns: CouponCampaignRepository,
    private val users: UserInitializationService,
    private val jdbc: JdbcTemplate,
    private val cleanup: DatabaseCleanUp,
    @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) private val redis: RedisTemplate<String, String>,
) {
    /** 충분한 슬롯으로 실제 발급 8개를 경쟁시켜 Redis 한도와 비동기 DB 결과를 각각 확인한다. */
    @Test
    fun `eight issuances compete for five Redis coupons before DB is written`() {
        admission.close()
        clearCouponTestState(redis)
        cleanup.truncateAllTables()
        preparation.prepare(CouponCampaign("다섯 장", "혜택", 5, id = 1), bootstrapConfirmed = true)
        preparation.openAfterQuiescence(true)
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val buyers = (1..8).map { users.create("구매자$it").id }
            val pending = buyers.map { buyer ->
                pool.submit(
                    Callable {
                    start.await()
                    try {
                        check(coupons.issue(buyer, 1).created)
                        "SUCCESS"
                    } catch (failure: CommerceException) {
                        failure.reason.name
                    }
                },
                )
            }
            start.countDown()
            val outcomes = pending.map { it.get(15, TimeUnit.SECONDS) }

            assertThat(outcomes.count { it == "SUCCESS" }).isEqualTo(5)
            assertThat(outcomes.count { it == "COUPON_SOLD_OUT" }).isEqualTo(3)
            assertThat(redis.opsForHash<String, String>().get(CouponDataKeys.campaign(1), "issued")).isEqualTo("5")
            assertThat(checkNotNull(jdbc.queryForObject("select count(*) from issued_coupons", Long::class.java))).isZero()
            assertThat(worker.runOnce()).isEqualTo(5)
            assertThat(checkNotNull(jdbc.queryForObject("select count(*) from issued_coupons", Long::class.java))).isEqualTo(5)
            assertThat(campaigns.find(1)!!.issuedCount).isEqualTo(5)
            assertThat(checkNotNull(redis.opsForSet().size(CouponPermitKeys.ACTIVE))).isZero()
        } finally {
            start.countDown()
            pool.shutdownNow()
            pool.awaitTermination(5, TimeUnit.SECONDS)
            admission.close()
            clearCouponTestState(redis)
            cleanup.truncateAllTables()
        }
    }
}
