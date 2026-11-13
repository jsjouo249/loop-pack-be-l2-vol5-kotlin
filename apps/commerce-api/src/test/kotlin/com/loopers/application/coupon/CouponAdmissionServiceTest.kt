package com.loopers.application.coupon

import com.loopers.domain.coupon.CouponAdmissionException
import com.loopers.domain.coupon.CouponAdmissionFailure
import com.loopers.domain.coupon.CouponPermits
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Redis permit 포트를 mock해 수용 상태 전이와 획득·반환·재개가 겹치는 순서를 검증한다. */
class CouponAdmissionServiceTest {
    private val permits = mock<CouponPermits>()
    private val service = CouponAdmissionService(permits, SimpleMeterRegistry(), 3, waitTimeoutMillis = 0)

    @Test
    fun `기본 수용은 닫혀 있고 명시적 확인 없이는 Redis를 호출하지 않는다`() {
        assertThatThrownBy { service.acquire() }.isInstanceOf(CouponAdmissionException::class.java)
        assertThatThrownBy { service.openAfterQuiescence(false) }.isInstanceOf(IllegalArgumentException::class.java)

        verifyNoInteractions(permits)
    }

    @Test
    fun `슬롯 부족은 수용을 닫지 않고 완료된 실행의 슬롯은 한 번만 반환한다`() {
        open()
        whenever(permits.acquire(any(), eq(3))).thenReturn(false, true)

        assertThatThrownBy { service.acquire() }.isInstanceOfSatisfying(CouponAdmissionException::class.java) {
            assertThat(it.reason).isEqualTo(CouponAdmissionFailure.FULL)
        }
        val permit = service.acquire()
        service.complete(permit, true)
        service.complete(permit, true)

        verify(permits).release(permit)
    }

    @Test
    fun `발급 결과가 불명이면 점유를 유지하고 수동 확인 전까지 수용을 닫는다`() {
        open()
        whenever(permits.acquire(any(), eq(3))).thenReturn(true)
        val permit = service.acquire()

        service.complete(permit, false)

        verify(permits, never()).release(any())
        assertThatThrownBy { service.acquire() }.isInstanceOf(CouponAdmissionException::class.java)
        assertThatThrownBy { service.openAfterQuiescence(false) }.isInstanceOf(IllegalArgumentException::class.java)
        service.openAfterQuiescence(true)
        verify(permits, times(2)).initializeAfterQuiescence()
    }

    /** Redis 획득이 아직 진행 중이어도 로컬 실행으로 추적해 정리·재개가 앞서지 못하도록 검증한다. */
    @Test
    fun `수용을 닫으면 신규 실행을 거절하고 진행 중인 획득이 끝나기 전에는 재개하지 못한다`() {
        open()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        whenever(permits.acquire(any(), eq(3))).thenAnswer {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            true
        }
        try {
            val pending = pool.submit(Callable { service.acquire() })
            check(entered.await(5, TimeUnit.SECONDS))
            service.close()

            assertThatThrownBy { service.acquire() }.isInstanceOf(CouponAdmissionException::class.java)
            assertThatThrownBy { service.openAfterQuiescence(true) }.isInstanceOf(IllegalStateException::class.java)
            verify(permits).initializeAfterQuiescence()

            release.countDown()
            val permit = pending.get(5, TimeUnit.SECONDS)
            service.complete(permit, true)
            service.openAfterQuiescence(true)

            verify(permits).release(permit)
            verify(permits, times(2)).initializeAfterQuiescence()
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    /** 반환 중인 실행은 여전히 추적하고, 같은 실행의 동시 완료 호출도 Redis 반환 한 번으로 처리한다. */
    @Test
    fun `반환 중에는 재개하지 못하고 중복 완료 호출도 슬롯을 한 번만 반환한다`() {
        open()
        whenever(permits.acquire(any(), eq(3))).thenReturn(true)
        val permit = service.acquire()
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        doAnswer {
            entered.countDown()
            check(finish.await(5, TimeUnit.SECONDS))
            null
        }.whenever(permits).release(permit)
        try {
            val pending = pool.submit { service.complete(permit, true) }
            check(entered.await(5, TimeUnit.SECONDS))
            service.close()
            pool.submit { service.complete(permit, true) }.get(5, TimeUnit.SECONDS)

            assertThatThrownBy { service.openAfterQuiescence(true) }.isInstanceOf(IllegalStateException::class.java)
            finish.countDown()
            pending.get(5, TimeUnit.SECONDS)
            service.openAfterQuiescence(true)

            verify(permits).release(permit)
        } finally {
            finish.countDown()
            pool.shutdownNow()
        }
    }

    /** 열기 도중 close가 와도 자동으로 열리지 않고, 이전 초기화가 끝나기 전 재초기화를 금지한다. */
    @Test
    fun `초기화 중 수용을 닫으면 열리지 않고 중복 초기화도 거절한다`() {
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        whenever(permits.initializeAfterQuiescence()).thenAnswer {
            entered.countDown()
            check(finish.await(5, TimeUnit.SECONDS))
            "primary"
        }
        try {
            val pending = pool.submit(Callable { runCatching { service.openAfterQuiescence(true) }.exceptionOrNull() })
            check(entered.await(5, TimeUnit.SECONDS))
            service.close()

            assertThatThrownBy { service.openAfterQuiescence(true) }.isInstanceOf(IllegalStateException::class.java)
            finish.countDown()
            assertThat(pending.get(5, TimeUnit.SECONDS)).isInstanceOf(CouponAdmissionException::class.java)
            assertThatThrownBy { service.acquire() }.isInstanceOf(CouponAdmissionException::class.java)
            verify(permits).initializeAfterQuiescence()
        } finally {
            finish.countDown()
            pool.shutdownNow()
        }
    }

    private fun open() {
        whenever(permits.initializeAfterQuiescence()).thenReturn("primary")
        service.openAfterQuiescence(true)
    }
}
