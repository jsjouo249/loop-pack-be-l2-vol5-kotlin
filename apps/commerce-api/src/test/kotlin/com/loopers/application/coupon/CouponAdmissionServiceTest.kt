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

class CouponAdmissionServiceTest {
    private val permits = mock<CouponPermits>()
    private val service = CouponAdmissionService(permits, SimpleMeterRegistry(), 3, waitTimeoutMillis = 0)

    @Test
    fun `starts closed and requires explicit confirmation before Redis is touched`() {
        assertThatThrownBy { service.acquire() }.isInstanceOf(CouponAdmissionException::class.java)
        assertThatThrownBy { service.openAfterQuiescence(false) }.isInstanceOf(IllegalArgumentException::class.java)

        verifyNoInteractions(permits)
    }

    @Test
    fun `full capacity does not close admission and completed execution is returned once`() {
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
    fun `unknown issuance keeps Redis occupancy and stays closed until manual confirmation`() {
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
    fun `close rejects newcomers but cannot reopen while a permit acquisition is unresolved`() {
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
    fun `return in progress blocks reopening and duplicate completion cannot release twice`() {
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
    fun `close during initialization prevents opening and overlapping resets`() {
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
