package com.loopers.application.coupon

import com.loopers.domain.coupon.CouponAdmissionException
import com.loopers.domain.coupon.CouponAdmissionFailure
import com.loopers.domain.coupon.CouponPermit
import com.loopers.domain.coupon.CouponPermits
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class CouponAdmissionWaitingTest {
    private val permits = mock<CouponPermits>()
    private val meters = SimpleMeterRegistry()

    /** 긴 재검사 간격보다 먼저 반환 알림으로 진행하고 서로 다른 실행 token을 유지하는지 확인한다. */
    @Test
    fun `return wakes a bounded waiter before the fallback retry interval`() {
        val occupied = AtomicBoolean()
        whenever(permits.acquire(any(), eq(1))).thenAnswer { occupied.compareAndSet(false, true) }
        doAnswer {
            occupied.set(false)
            null
        }.whenever(permits).release(any())
        val service = open(timeout = 2_000, retry = 10_000)
        val first = service.acquire()
        val pool = Executors.newSingleThreadExecutor()
        try {
            val pending = pool.submit(Callable { service.acquire() })
            awaitWaiting()
            assertThat(pending.isDone).isFalse()

            service.complete(first, true)
            val next = pending.get(1, TimeUnit.SECONDS)
            assertThat(next.token).isNotEqualTo(first.token)
            service.complete(next, true)

            verify(permits, times(2)).release(any())
            assertThat(waiting()).isZero()
        } finally {
            service.close()
            pool.shutdownNow()
            pool.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `deadline rejects without another Redis attempt or a foreign token release`() {
        whenever(permits.acquire(any(), eq(1))).thenReturn(false)
        val service = open(timeout = 100, retry = 10_000)
        val started = System.nanoTime()

        assertThatThrownBy { service.acquire() }.isInstanceOfSatisfying(CouponAdmissionException::class.java) {
            assertThat(it.reason).isEqualTo(CouponAdmissionFailure.FULL)
        }

        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isGreaterThanOrEqualTo(80)
        verify(permits).acquire(any(), eq(1))
        verify(permits, never()).release(any())
        assertThat(waiting()).isZero()
        assertThat(meters.counter("coupon.admission", "outcome", "wait_timeout").count()).isEqualTo(1.0)
        service.close()
        service.openAfterQuiescence(true)
    }

    /** 한 명이 대기하는 동안 초과 등록은 거절하고 닫힘이 기존 대기를 즉시 종결시키는지 확인한다. */
    @Test
    fun `waiting limit rejects excess and closing wakes already waiting requests`() {
        whenever(permits.acquire(any(), eq(1))).thenReturn(false)
        val service = open(timeout = 10_000, maxWaiters = 1, retry = 10_000)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val pending = pool.submit(Callable { runCatching { service.acquire() }.exceptionOrNull() })
            awaitWaiting()

            assertThatThrownBy { service.acquire() }.isInstanceOfSatisfying(CouponAdmissionException::class.java) {
                assertThat(it.reason).isEqualTo(CouponAdmissionFailure.FULL)
            }
            assertThat(waiting()).isEqualTo(1.0)
            assertThat(meters.counter("coupon.admission", "outcome", "waiting_full").count()).isEqualTo(1.0)

            service.close()
            assertThat(pending.get(1, TimeUnit.SECONDS)).isInstanceOfSatisfying(CouponAdmissionException::class.java) {
                assertThat(it.reason).isEqualTo(CouponAdmissionFailure.UNAVAILABLE)
            }
            assertThat(waiting()).isZero()
            service.openAfterQuiescence(true)
        } finally {
            service.close()
            pool.shutdownNow()
            pool.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    /** 로컬 대기 중단은 Redis 점유를 건드리지 않고 interrupt 상태·대기 자원만 정리한다. */
    @Test
    fun `interrupted local wait preserves interrupt status cleans waiting and leaves healthy admission open`() {
        whenever(permits.acquire(any(), eq(1))).thenReturn(false)
        val service = open(timeout = 10_000, retry = 10_000)
        val thread = AtomicReference<Thread>()
        val pool = Executors.newSingleThreadExecutor()
        try {
            val pending = pool.submit(
                Callable {
                thread.set(Thread.currentThread())
                val failure = runCatching { service.acquire() }.exceptionOrNull()
                failure to Thread.currentThread().isInterrupted
            },
            )
            awaitWaiting()
            thread.get().interrupt()
            val result = pending.get(1, TimeUnit.SECONDS)

            assertThat(result.first).isInstanceOfSatisfying(CouponAdmissionException::class.java) {
                assertThat(it.reason).isEqualTo(CouponAdmissionFailure.CANCELLED)
            }
            assertThat(result.second).isTrue()
            assertThat(waiting()).isZero()
            verify(permits, never()).release(any())

            whenever(permits.acquire(any(), eq(1))).thenReturn(true)
            service.complete(service.acquire(), true)
        } finally {
            service.close()
            pool.shutdownNow()
            pool.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `Redis retry error closes admission immediately without treating uncertainty as another full result`() {
        whenever(permits.acquire(any(), eq(1))).thenReturn(false).thenThrow(IllegalStateException("response unknown"))
        val service = open(timeout = 2_000, retry = 10)

        assertThatThrownBy { service.acquire() }.isInstanceOfSatisfying(CouponAdmissionException::class.java) {
            assertThat(it.reason).isEqualTo(CouponAdmissionFailure.UNAVAILABLE)
        }
        val tokens = argumentCaptor<CouponPermit>()
        verify(permits, times(2)).acquire(tokens.capture(), eq(1))
        assertThat(tokens.allValues.toSet()).hasSize(1)
        verify(permits, never()).release(any())
        assertThat(waiting()).isZero()
        assertThatThrownBy { service.acquire() }.isInstanceOf(CouponAdmissionException::class.java)
    }

    @Test
    fun `interruption during Redis acquisition is an unknown outcome rather than a safe local cancellation`() {
        whenever(permits.acquire(any(), eq(1))).thenAnswer { throw InterruptedException("SADD outcome unknown") }
        val service = open(timeout = 2_000, retry = 10)

        assertThatThrownBy { service.acquire() }.isInstanceOfSatisfying(CouponAdmissionException::class.java) {
            assertThat(it.reason).isEqualTo(CouponAdmissionFailure.UNAVAILABLE)
        }
        verify(permits).acquire(any(), eq(1))
        verify(permits, never()).release(any())
        assertThat(waiting()).isZero()
        assertThatThrownBy { service.acquire() }.isInstanceOf(CouponAdmissionException::class.java)
    }

    private fun open(timeout: Long, maxWaiters: Int = 50, retry: Long): CouponAdmissionService {
        whenever(permits.initializeAfterQuiescence()).thenReturn("primary")
        return CouponAdmissionService(permits, meters, 1, timeout, maxWaiters, retry).also { it.openAfterQuiescence(true) }
    }

    private fun waiting(): Double = meters.get("coupon.admission.waiting").gauge().value()

    private fun awaitWaiting() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (waiting() != 1.0 && System.nanoTime() < deadline) Thread.sleep(1)
        assertThat(waiting()).isEqualTo(1.0)
    }
}
