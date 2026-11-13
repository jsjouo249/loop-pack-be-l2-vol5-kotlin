package com.loopers.domain.coupon

import java.time.Instant

data class IssuedCoupon(
    val campaignId: Long,
    val userId: Long,
    val benefitDescription: String,
    val issuedAt: Instant = Instant.now(),
    val id: Long = 0,
) {
    init {
        require(campaignId > 0 && userId > 0 && id >= 0)
        require(benefitDescription.isNotBlank() && benefitDescription.length <= 255)
    }
}

interface CouponCampaignRepository {
    fun find(id: Long): CouponCampaign?
    fun findForUpdate(id: Long): CouponCampaign?
    fun insert(campaign: CouponCampaign): CouponCampaign
    fun save(campaign: CouponCampaign)
}

interface IssuedCouponRepository {
    fun findById(id: Long): IssuedCoupon?
    fun find(userId: Long, campaignId: Long): IssuedCoupon?
    fun findByUser(userId: Long): List<IssuedCoupon>
    fun insert(coupon: IssuedCoupon): IssuedCoupon
}
