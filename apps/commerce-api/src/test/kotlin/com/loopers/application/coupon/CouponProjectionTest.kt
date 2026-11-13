package com.loopers.application.coupon

import com.loopers.domain.coupon.CouponCampaign
import com.loopers.domain.coupon.CouponCampaignRepository
import com.loopers.domain.coupon.CouponEventRecord
import com.loopers.domain.coupon.CouponIssuedEvent
import com.loopers.domain.coupon.CouponIssueLog
import com.loopers.domain.coupon.IssuedCoupon
import com.loopers.domain.coupon.IssuedCouponRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** repository와 worker의 TX 서비스를 mock해 멱등 판단·호출 순서를 검증한다. 실제 DB commit·rollback 검증은 통합 테스트가 담당한다. */
class CouponProjectionTest {
    private val campaigns = mock<CouponCampaignRepository>()
    private val coupons = mock<IssuedCouponRepository>()
    private val campaign = CouponCampaign("행사", "혜택", 5, id = 1)
    private val coupon = IssuedCoupon(1, 1, "혜택", id = 10)
    private val fact = CouponIssuedEvent(campaign, coupon)
    private val transaction = CouponProjectionTransaction(campaigns, coupons)

    @Test
    fun `이미 DB에 반영한 같은 발급 사실은 수량을 다시 늘리거나 쿠폰을 삽입하지 않는다`() {
        whenever(campaigns.findForUpdate(1)).thenReturn(campaign)
        whenever(coupons.findById(10)).thenReturn(coupon)

        transaction.project(fact)

        verify(campaigns, never()).save(any())
        verify(coupons, never()).insert(any())
    }

    @Test
    fun `같은 쿠폰 ID에 다른 발급 사실이 있으면 DB 반영을 거절한다`() {
        whenever(campaigns.findForUpdate(1)).thenReturn(campaign)
        whenever(coupons.findById(10)).thenReturn(coupon.copy(userId = 2))

        assertThatThrownBy { transaction.project(fact) }.isInstanceOf(IllegalStateException::class.java)

        verify(campaigns, never()).save(any())
    }

    @Test
    fun `새 발급 사실은 캠페인 발급 수를 늘리고 쿠폰을 함께 저장한다`() {
        whenever(campaigns.findForUpdate(1)).thenReturn(campaign)

        transaction.project(fact)

        assertThat(campaign.issuedCount).isEqualTo(1)
        verify(campaigns).save(campaign)
        verify(coupons).insert(coupon)
    }

    @Test
    fun `worker는 DB 반영 호출이 반환된 뒤 ACK하고 ACK 실패 시 수용을 닫는다`() {
        val events = mock<CouponIssueLog>()
        val transactions = mock<CouponProjectionTransaction>()
        val admission = mock<CouponAdmissionService>()
        val worker = CouponProjectionWorker(events, transactions, admission)
        whenever(events.readPendingOrNew(20)).thenReturn(listOf(CouponEventRecord("1-0", fact)))
        doThrow(IllegalStateException("ACK response lost")).whenever(events).acknowledge("1-0")

        assertThat(worker.runOnce()).isZero()

        inOrder(transactions, events) {
            verify(transactions).project(fact)
            verify(events).acknowledge("1-0")
        }
        verify(admission).close()
    }

    @Test
    fun `DB 반영 호출이 실패하면 ACK하지 않고 신규 수용은 유지한다`() {
        val events = mock<CouponIssueLog>()
        val transactions = mock<CouponProjectionTransaction>()
        val admission = mock<CouponAdmissionService>()
        val worker = CouponProjectionWorker(events, transactions, admission)
        whenever(events.readPendingOrNew(20)).thenReturn(listOf(CouponEventRecord("1-0", fact)))
        doThrow(IllegalStateException("DB unavailable")).whenever(transactions).project(fact)

        assertThat(worker.runOnce()).isZero()

        verify(events, never()).acknowledge(any())
        verify(admission, never()).close()
    }
}
