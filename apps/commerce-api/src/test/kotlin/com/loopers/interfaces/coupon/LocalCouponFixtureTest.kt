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

class LocalCouponFixtureTest {
    @Test
    fun `unavailable Redis leaves coupon closed instead of aborting other local features at startup`() {
        val campaigns = mock<CouponCampaignPreparation>()
        whenever(campaigns.prepare(any(), eq(false)))
            .thenThrow(CouponAdmissionException(CouponAdmissionFailure.UNAVAILABLE))
        val fixture = LocalCouponFixture(campaigns, false, false)

        assertThatCode { fixture.run(DefaultApplicationArguments()) }.doesNotThrowAnyException()

        verify(campaigns, never()).openAfterQuiescence(any())
    }
}
