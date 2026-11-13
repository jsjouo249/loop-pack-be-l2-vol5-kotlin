package com.loopers.domain.coupon

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import com.loopers.domain.commerce.CommerceException
import com.loopers.domain.commerce.CommerceFailure

/** Spring·Redis·DB 없이 캠페인의 발급 한도와 활성 상태 불변식을 검증한다. */
class CouponCampaignTest {
    @Test
    fun `발급은 한도를 소모하고 한도 초과 거절 후에도 발급 수를 유지한다`() {
        val campaign = CouponCampaign("행사", "5,000원 할인권", 1)

        campaign.issue()

        assertThat(campaign.issuedCount).isEqualTo(1)
        assertThatThrownBy { campaign.issue() }.isInstanceOf(RuntimeException::class.java)
        assertThat(campaign.issuedCount).isEqualTo(1)
    }

    @Test
    fun `유효하지 않은 발급 한도와 복원된 발급 수는 허용하지 않는다`() {
        assertThatThrownBy { CouponCampaign("행사", "5,000원 할인권", 0) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { CouponCampaign("행사", "5,000원 할인권", 2, issuedCount = 3) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { CouponCampaign("행사", "5,000원 할인권", 2, issuedCount = -1) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `비활성 캠페인은 발급 수를 늘리지 않고 신규 발급을 거절한다`() {
        val campaign = CouponCampaign("종료된 행사", "5,000원 할인권", 2)
        campaign.issue()
        campaign.deactivate()

        assertThatThrownBy { campaign.issue() }.isInstanceOfSatisfying(CommerceException::class.java) {
            assertThat(it.reason).isEqualTo(CommerceFailure.COUPON_CAMPAIGN_INACTIVE)
        }
        assertThat(campaign.issuedCount).isEqualTo(1)
    }
}
