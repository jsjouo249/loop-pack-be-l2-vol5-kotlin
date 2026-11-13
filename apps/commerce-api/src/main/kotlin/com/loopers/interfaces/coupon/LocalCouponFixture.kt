package com.loopers.interfaces.coupon

import com.loopers.application.coupon.CouponCampaignPreparation
import com.loopers.domain.coupon.CouponAdmissionException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

@Component
@Profile("local")
class LocalCouponFixture(
    private val campaigns: CouponCampaignPreparation,
    @Value("\${coupon.ssot.bootstrap-confirmed:false}") private val bootstrapConfirmed: Boolean,
    @Value("\${coupon.admission.open-after-quiescence:false}") private val openAfterQuiescence: Boolean,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        val log = LoggerFactory.getLogger(javaClass)
        try {
            val campaign = campaigns.prepare(bootstrapConfirmed = bootstrapConfirmed)
            log.info("local_coupon_campaign id={} limit={}", campaign.id, campaign.issuanceLimit)

            if (openAfterQuiescence) campaigns.openAfterQuiescence(confirmed = true)
        } catch (failure: CouponAdmissionException) {
            log.error("local_coupon_setup unavailable; coupon admission remains closed", failure)
        }
    }
}
