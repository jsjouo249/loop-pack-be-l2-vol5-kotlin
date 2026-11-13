package com.loopers.domain.coupon

import com.loopers.domain.commerce.CommerceException
import com.loopers.domain.commerce.CommerceFailure

class CouponCampaign(
    val name: String,
    val benefitDescription: String,
    val issuanceLimit: Long,
    val id: Long = 0,
    issuedCount: Long = 0,
    issuanceEnabled: Boolean = true,
) {
    var issuedCount: Long = issuedCount
        private set

    var issuanceEnabled: Boolean = issuanceEnabled
        private set

    init {
        if (name.isBlank() || name.length > 255) throw CommerceException(CommerceFailure.INVALID_NAME)
        require(benefitDescription.isNotBlank() && benefitDescription.length <= 255)
        require(issuanceLimit > 0 && issuedCount in 0..issuanceLimit)
    }

    fun issue() {
        if (!issuanceEnabled) throw CommerceException(CommerceFailure.COUPON_CAMPAIGN_INACTIVE)
        if (issuedCount >= issuanceLimit) throw CommerceException(CommerceFailure.COUPON_SOLD_OUT)

        issuedCount++
    }

    fun deactivate() {
        issuanceEnabled = false
    }
}
