package com.loopers.application.coupon

import com.loopers.domain.commerce.CommerceException
import com.loopers.domain.commerce.CommerceFailure
import com.loopers.domain.coupon.CouponAdmissionException
import com.loopers.domain.coupon.CouponAdmissionFailure
import com.loopers.domain.coupon.CouponAuthority
import com.loopers.domain.coupon.CouponDecision
import com.loopers.domain.coupon.CouponPermits
import com.loopers.domain.coupon.IssuedCoupon
import com.loopers.domain.user.User
import com.loopers.domain.user.UserRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

class CouponApplicationServiceTest {
    private val users = mock<UserRepository>()
    private val authority = mock<CouponAuthority>()
    private val permits = mock<CouponPermits>()
    private val admission = CouponAdmissionService(permits, SimpleMeterRegistry(), 3, waitTimeoutMillis = 0)
    private val service = CouponApplicationService(users, authority, admission)
    private val coupon = IssuedCoupon(10, 1, "5,000원 할인권", id = 100)

    @BeforeEach
    fun setup() {
        whenever(users.find(1)).thenReturn(User("구매자"))
        whenever(permits.initializeAfterQuiescence()).thenReturn("primary")
        admission.openAfterQuiescence(true)
        clearInvocations(permits)
        whenever(permits.acquire(any(), eq(3))).thenReturn(true)
        whenever(authority.issue(eq(1), eq(10), any())).thenReturn(CouponDecision(coupon, true))
    }

    @Test
    fun `existing Redis result is returned without a new execution slot even while admission is closed`() {
        whenever(authority.find(1, 10)).thenReturn(coupon)
        admission.close()

        assertThat(service.issue(1, 10)).isEqualTo(CouponIssueResult(coupon, false))

        verifyNoInteractions(permits)
        verify(authority, never()).issue(any(), any(), any())
    }

    @Test
    fun `Redis issuance result is returned before any DB projection`() {
        val result = service.issue(1, 10)

        assertThat(result).isEqualTo(CouponIssueResult(coupon, true))
        verify(permits).release(any())
    }

    @Test
    fun `duplicate discovered inside issuance Lua returns existing result and releases its own execution`() {
        whenever(authority.issue(eq(1), eq(10), any())).thenReturn(CouponDecision(coupon, false))

        assertThat(service.issue(1, 10)).isEqualTo(CouponIssueResult(coupon, false))

        verify(permits).release(any())
    }

    @Test
    fun `sold out is a completed business rejection and returns the slot`() {
        whenever(authority.issue(eq(1), eq(10), any())).thenThrow(CommerceException(CommerceFailure.COUPON_SOLD_OUT))

        assertThatThrownBy { service.issue(1, 10) }.isInstanceOfSatisfying(CommerceException::class.java) {
            assertThat(it.reason).isEqualTo(CommerceFailure.COUPON_SOLD_OUT)
        }
        verify(permits).release(any())
    }

    @Test
    fun `full capacity never executes issuance Lua`() {
        whenever(permits.acquire(any(), eq(3))).thenReturn(false)

        assertThatThrownBy { service.issue(1, 10) }.isInstanceOfSatisfying(CouponAdmissionException::class.java) {
            assertThat(it.reason).isEqualTo(CouponAdmissionFailure.FULL)
        }
        verify(authority, never()).issue(any(), any(), any())
    }

    @Test
    fun `unknown acquisition closes admission without issuing or returning a possibly active token`() {
        whenever(permits.acquire(any(), eq(3))).thenThrow(CouponAdmissionException(CouponAdmissionFailure.UNAVAILABLE))

        assertThatThrownBy { service.issue(1, 10) }.isInstanceOf(CouponAdmissionException::class.java)

        verify(authority, never()).issue(any(), any(), any())
        verify(permits, never()).release(any())
        assertThatThrownBy { admission.acquire() }.isInstanceOf(CouponAdmissionException::class.java)
    }

    @Test
    fun `unknown issuance outcome retains occupancy and a retry recovers the same stored coupon`() {
        whenever(authority.issue(eq(1), eq(10), any())).thenThrow(CouponAdmissionException(CouponAdmissionFailure.UNAVAILABLE))

        assertThatThrownBy { service.issue(1, 10) }.isInstanceOf(CouponAdmissionException::class.java)
        verify(permits, never()).release(any())
        assertThatThrownBy { admission.acquire() }.isInstanceOf(CouponAdmissionException::class.java)

        whenever(authority.find(1, 10)).thenReturn(coupon)
        assertThat(service.issue(1, 10)).isEqualTo(CouponIssueResult(coupon, false))
        verify(permits).acquire(any(), eq(3))
    }

    @Test
    fun `return failure preserves Redis success and closes new admission`() {
        doThrow(IllegalStateException("response lost")).whenever(permits).release(any())

        assertThat(service.issue(1, 10).coupon).isEqualTo(coupon)
        assertThatThrownBy { admission.acquire() }.isInstanceOf(CouponAdmissionException::class.java)
    }

    @Test
    fun `owned list uses Redis authority rather than a possibly lagging DB projection`() {
        whenever(authority.owned(1)).thenReturn(listOf(coupon))

        assertThat(service.owned(1)).containsExactly(coupon)
        verifyNoInteractions(permits)
    }

    @Test
    fun `Redis lookup failure closes issuance and never returns an empty successful list`() {
        whenever(authority.owned(1)).thenThrow(CouponAdmissionException(CouponAdmissionFailure.UNAVAILABLE))

        assertThatThrownBy { service.owned(1) }.isInstanceOf(CouponAdmissionException::class.java)
        assertThatThrownBy { admission.acquire() }.isInstanceOf(CouponAdmissionException::class.java)
    }
}
