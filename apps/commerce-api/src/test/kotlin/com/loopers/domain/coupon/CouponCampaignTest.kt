package com.loopers.domain.coupon

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import com.loopers.domain.commerce.CommerceException
import com.loopers.domain.commerce.CommerceFailure

class CouponCampaignTest {
    @Test
    fun `issuing consumes quota and completing an execution does not restore it`() {
        val campaign = CouponCampaign("행사", "5,000원 할인권", 1)

        campaign.issue()

        assertThat(campaign.issuedCount).isEqualTo(1)
        assertThatThrownBy { campaign.issue() }.isInstanceOf(RuntimeException::class.java)
        assertThat(campaign.issuedCount).isEqualTo(1)
    }

    @Test
    fun `invalid limits and restored counts cannot enter the domain`() {
        assertThatThrownBy { CouponCampaign("행사", "5,000원 할인권", 0) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { CouponCampaign("행사", "5,000원 할인권", 2, issuedCount = 3) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { CouponCampaign("행사", "5,000원 할인권", 2, issuedCount = -1) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `inactive campaign rejects new issuance without consuming quota`() {
        val campaign = CouponCampaign("종료된 행사", "5,000원 할인권", 2)
        campaign.issue()
        campaign.deactivate()

        assertThatThrownBy { campaign.issue() }.isInstanceOfSatisfying(CommerceException::class.java) {
            assertThat(it.reason).isEqualTo(CommerceFailure.COUPON_CAMPAIGN_INACTIVE)
        }
        assertThat(campaign.issuedCount).isEqualTo(1)
    }
}
