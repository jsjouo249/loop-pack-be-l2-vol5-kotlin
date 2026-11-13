package com.loopers.interfaces.coupon

import com.loopers.application.coupon.CouponProjectionWorker
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = ["coupon.projection.enabled"], havingValue = "true")
class CouponProjectionSchedule(private val worker: CouponProjectionWorker) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${coupon.projection.poll-delay-ms:1000}")
    fun tick() {
        try {
            worker.runOnce()
        } catch (failure: Exception) {
            log.error("coupon_projection read_failed", failure)
        }
    }
}
