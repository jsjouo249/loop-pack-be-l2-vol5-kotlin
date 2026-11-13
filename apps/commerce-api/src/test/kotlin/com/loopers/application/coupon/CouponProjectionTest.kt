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

class CouponProjectionTest {
    private val campaigns = mock<CouponCampaignRepository>()
    private val coupons = mock<IssuedCouponRepository>()
    private val campaign = CouponCampaign("행사", "혜택", 5, id = 1)
    private val coupon = IssuedCoupon(1, 1, "혜택", id = 10)
    private val fact = CouponIssuedEvent(campaign, coupon)
    private val transaction = CouponProjectionTransaction(campaigns, coupons)

    @Test
    fun `same already projected fact does not increase count again`() {
        whenever(campaigns.findForUpdate(1)).thenReturn(campaign)
        whenever(coupons.findById(10)).thenReturn(coupon)

        transaction.project(fact)

        verify(campaigns, never()).save(any())
        verify(coupons, never()).insert(any())
    }

    @Test
    fun `conflicting coupon ID cannot be acknowledged as successful replay`() {
        whenever(campaigns.findForUpdate(1)).thenReturn(campaign)
        whenever(coupons.findById(10)).thenReturn(coupon.copy(userId = 2))

        assertThatThrownBy { transaction.project(fact) }.isInstanceOf(IllegalStateException::class.java)

        verify(campaigns, never()).save(any())
    }

    @Test
    fun `new projection updates count and coupon together`() {
        whenever(campaigns.findForUpdate(1)).thenReturn(campaign)

        transaction.project(fact)

        assertThat(campaign.issuedCount).isEqualTo(1)
        verify(campaigns).save(campaign)
        verify(coupons).insert(coupon)
    }

    @Test
    fun `worker acknowledges only after transaction returns and retries commit without ACK`() {
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
    fun `failed DB transaction stays pending and does not roll back the Redis fact`() {
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
