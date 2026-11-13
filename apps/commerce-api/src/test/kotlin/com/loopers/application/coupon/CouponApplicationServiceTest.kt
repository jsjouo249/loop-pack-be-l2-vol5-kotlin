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

/** 사용자·발급 원본·permit 포트를 mock해 기존 결과 반환과 발급 성공·거절·결과 불명의 조율을 검증한다. */
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
    fun `수용이 닫혀 있어도 기존 Redis 발급 결과는 새 슬롯 없이 반환한다`() {
        whenever(authority.find(1, 10)).thenReturn(coupon)
        admission.close()

        assertThat(service.issue(1, 10)).isEqualTo(CouponIssueResult(coupon, false))

        verifyNoInteractions(permits)
        verify(authority, never()).issue(any(), any(), any())
    }

    @Test
    fun `DB 비동기 반영을 기다리지 않고 Redis 발급 결과를 반환한다`() {
        val result = service.issue(1, 10)

        assertThat(result).isEqualTo(CouponIssueResult(coupon, true))
        verify(permits).release(any())
    }

    @Test
    fun `발급 Lua에서 중복 결과를 받으면 기존 쿠폰을 반환하고 자기 슬롯을 해제한다`() {
        whenever(authority.issue(eq(1), eq(10), any())).thenReturn(CouponDecision(coupon, false))

        assertThat(service.issue(1, 10)).isEqualTo(CouponIssueResult(coupon, false))

        verify(permits).release(any())
    }

    @Test
    fun `품절은 확정된 업무 거절로 처리하고 슬롯을 반환한다`() {
        whenever(authority.issue(eq(1), eq(10), any())).thenThrow(CommerceException(CommerceFailure.COUPON_SOLD_OUT))

        assertThatThrownBy { service.issue(1, 10) }.isInstanceOfSatisfying(CommerceException::class.java) {
            assertThat(it.reason).isEqualTo(CommerceFailure.COUPON_SOLD_OUT)
        }
        verify(permits).release(any())
    }

    @Test
    fun `슬롯을 획득하지 못하면 발급 Lua를 호출하지 않는다`() {
        whenever(permits.acquire(any(), eq(3))).thenReturn(false)

        assertThatThrownBy { service.issue(1, 10) }.isInstanceOfSatisfying(CouponAdmissionException::class.java) {
            assertThat(it.reason).isEqualTo(CouponAdmissionFailure.FULL)
        }
        verify(authority, never()).issue(any(), any(), any())
    }

    @Test
    fun `획득 결과가 불명이면 발급과 슬롯 반환 없이 수용을 닫는다`() {
        whenever(permits.acquire(any(), eq(3))).thenThrow(CouponAdmissionException(CouponAdmissionFailure.UNAVAILABLE))

        assertThatThrownBy { service.issue(1, 10) }.isInstanceOf(CouponAdmissionException::class.java)

        verify(authority, never()).issue(any(), any(), any())
        verify(permits, never()).release(any())
        assertThatThrownBy { admission.acquire() }.isInstanceOf(CouponAdmissionException::class.java)
    }

    @Test
    fun `발급 결과 불명은 점유를 유지하고 재요청은 저장된 같은 쿠폰을 반환한다`() {
        whenever(authority.issue(eq(1), eq(10), any())).thenThrow(CouponAdmissionException(CouponAdmissionFailure.UNAVAILABLE))

        assertThatThrownBy { service.issue(1, 10) }.isInstanceOf(CouponAdmissionException::class.java)
        verify(permits, never()).release(any())
        assertThatThrownBy { admission.acquire() }.isInstanceOf(CouponAdmissionException::class.java)

        whenever(authority.find(1, 10)).thenReturn(coupon)
        assertThat(service.issue(1, 10)).isEqualTo(CouponIssueResult(coupon, false))
        verify(permits).acquire(any(), eq(3))
    }

    @Test
    fun `슬롯 반환에 실패해도 Redis 발급 성공을 유지하고 신규 수용을 닫는다`() {
        doThrow(IllegalStateException("response lost")).whenever(permits).release(any())

        assertThat(service.issue(1, 10).coupon).isEqualTo(coupon)
        assertThatThrownBy { admission.acquire() }.isInstanceOf(CouponAdmissionException::class.java)
    }

    @Test
    fun `보유 목록은 Redis 원본에서 조회하고 슬롯을 획득하지 않는다`() {
        whenever(authority.owned(1)).thenReturn(listOf(coupon))

        assertThat(service.owned(1)).containsExactly(coupon)
        verifyNoInteractions(permits)
    }

    @Test
    fun `Redis 조회 실패는 빈 목록 성공으로 바꾸지 않고 신규 수용을 닫는다`() {
        whenever(authority.owned(1)).thenThrow(CouponAdmissionException(CouponAdmissionFailure.UNAVAILABLE))

        assertThatThrownBy { service.owned(1) }.isInstanceOf(CouponAdmissionException::class.java)
        assertThatThrownBy { admission.acquire() }.isInstanceOf(CouponAdmissionException::class.java)
    }
}
