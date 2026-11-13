package com.loopers.application.coupon

import com.loopers.domain.coupon.CouponAuthority
import com.loopers.domain.coupon.CouponCampaign
import org.springframework.stereotype.Service

@Service
class CouponCampaignPreparation(
    private val authority: CouponAuthority,
    private val admission: CouponAdmissionService,
) {
    fun prepare(
        campaign: CouponCampaign = CouponCampaign("로컬 쿠폰 발급 행사", "5,000원 할인권", 100, id = 1),
        bootstrapConfirmed: Boolean = false,
    ): CouponCampaign {
        admission.requireQuiescentClosed()
        authority.prepare(campaign, bootstrapConfirmed)

        return campaign
    }

    fun openAfterQuiescence(confirmed: Boolean) {
        require(confirmed) { "Confirm all previous executions have ended and Redis issuance data is consistent" }
        authority.verifyReady()
        admission.openAfterQuiescence(confirmed)
    }
}
