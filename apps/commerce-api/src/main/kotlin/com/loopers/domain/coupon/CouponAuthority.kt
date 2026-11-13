package com.loopers.domain.coupon

data class CouponDecision(val coupon: IssuedCoupon, val created: Boolean)

data class CouponIssuedEvent(val campaign: CouponCampaign, val coupon: IssuedCoupon) {
    init {
        require(campaign.id > 0 && coupon.id > 0 && coupon.campaignId == campaign.id)
        require(coupon.benefitDescription == campaign.benefitDescription)
    }
}

data class CouponEventRecord(val recordId: String, val event: CouponIssuedEvent)

/** Redis 발급 원본. DB projection의 반영 여부는 발급 결과에 영향을 주지 않는다. */
interface CouponAuthority {
    fun prepare(campaign: CouponCampaign, bootstrapConfirmed: Boolean)
    fun verifyReady()
    fun find(userId: Long, campaignId: Long): IssuedCoupon?
    fun owned(userId: Long): List<IssuedCoupon>
    fun issue(userId: Long, campaignId: Long, permit: CouponPermit): CouponDecision
}

interface CouponIssueLog {
    fun readPendingOrNew(limit: Int): List<CouponEventRecord>
    fun acknowledge(recordId: String)
}
