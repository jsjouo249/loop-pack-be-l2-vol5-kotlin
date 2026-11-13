package com.loopers.interfaces.coupon

import com.loopers.application.coupon.CouponCampaignPreparation
import com.loopers.domain.coupon.CouponAdmissionException
import com.loopers.domain.coupon.CouponAdmissionFailure
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.boot.DefaultApplicationArguments

/** 캠페인 준비 서비스를 mock해 쿠폰 준비 실패가 로컬 앱 시작에 전파되지 않는지 검증한다. */
class LocalCouponFixtureTest {
    @Test
    fun `Redis 준비에 실패하면 앱 시작을 중단하지 않고 쿠폰 수용을 닫아 둔다`() {
        val campaigns = mock<CouponCampaignPreparation>()
        whenever(campaigns.prepare(any(), eq(false)))
            .thenThrow(CouponAdmissionException(CouponAdmissionFailure.UNAVAILABLE))
        val fixture = LocalCouponFixture(campaigns, false, false)

        assertThatCode { fixture.run(DefaultApplicationArguments()) }.doesNotThrowAnyException()

        verify(campaigns, never()).openAfterQuiescence(any())
    }
}
