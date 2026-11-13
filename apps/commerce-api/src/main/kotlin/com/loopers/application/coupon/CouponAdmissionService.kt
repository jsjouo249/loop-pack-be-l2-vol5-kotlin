package com.loopers.application.coupon

import com.loopers.domain.coupon.CouponAdmissionException
import com.loopers.domain.coupon.CouponAdmissionFailure
import com.loopers.domain.coupon.CouponPermit
import com.loopers.domain.coupon.CouponPermits
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@Service
class CouponAdmissionService(
    private val permits: CouponPermits,
    private val meters: MeterRegistry,
    @Value("\${coupon.admission.capacity:3}") private val capacity: Int,
    @Value("\${coupon.admission.wait-timeout-ms:30000}") private val waitTimeoutMillis: Long = 30_000,
    @Value("\${coupon.admission.max-waiters:50}") private val maxWaiters: Int = 50,
    @Value("\${coupon.admission.retry-interval-ms:200}") private val retryIntervalMillis: Long = 200,
) {
    private enum class State { CLOSED, OPENING, OPEN }
    private enum class Execution { ACQUIRING, WAITING, RUNNING, COMPLETING }

    private val log = LoggerFactory.getLogger(javaClass)
    private val monitor = Object()
    private var state = State.CLOSED
    private var primaryId: String? = null
    private var preparing = false
    private val running = mutableMapOf<CouponPermit, Execution>()
    private val waiting = AtomicInteger()
    private val waitNanos = TimeUnit.MILLISECONDS.toNanos(waitTimeoutMillis)
    private val retryNanos = TimeUnit.MILLISECONDS.toNanos(retryIntervalMillis)

    init {
        require(capacity > 0 && waitTimeoutMillis >= 0 && maxWaiters >= 0 && retryIntervalMillis > 0)
        Gauge.builder("coupon.admission.waiting", waiting) { it.get().toDouble() }.register(meters)
    }

    /** 기존 발급 실행과 Redis 작업의 종료를 운영자가 확인한 경우에만 자기 Redis 범위를 정리하고 수용을 연다. */
    fun openAfterQuiescence(confirmed: Boolean) {
        require(confirmed) { "Confirm all previous issuance executions and Redis work have ended" }
        synchronized(monitor) {
            check(state == State.CLOSED && running.isEmpty() && !preparing) {
                "Close admission and finish local executions before opening"
            }

            preparing = true
            state = State.OPENING
        }

        try {
            val identity = permits.initializeAfterQuiescence()
            synchronized(monitor) {
                check(state == State.OPENING) { "Admission was closed while preparing Redis" }
                primaryId = identity
                state = State.OPEN
            }
        } catch (failure: Exception) {
            close()
            count("open_failed")
            throw CouponAdmissionException(CouponAdmissionFailure.UNAVAILABLE, failure)
        } finally {
            synchronized(monitor) { preparing = false }
        }
    }

    fun requireQuiescentClosed() {
        synchronized(monitor) {
            check(state == State.CLOSED && running.isEmpty() && !preparing) {
                "Close admission and finish executions before preparing campaigns"
            }
        }
    }

    fun close() {
        synchronized(monitor) {
            state = State.CLOSED
            monitor.notifyAll()
        }
    }

    /**
     * 같은 실행 token으로 확정된 FULL만 재시도한다. 제한된 로컬 대기·deadline·jitter를 사용하고,
     * 결과 불명은 재시도하지 않는다. 대기·Redis 호출도 추적해 종료 전 재초기화를 막는다.
     */
    fun acquire(): CouponPermit {
        val started = System.nanoTime()
        val permit = synchronized(monitor) {
            ensureOpen()
            CouponPermit(UUID.randomUUID().toString(), checkNotNull(primaryId)).also { running[it] = Execution.ACQUIRING }
        }
        var queued = false
        var granted = false
        try {
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException("Coupon admission interrupted")
                val acquired = try {
                    permits.acquire(permit, capacity)
                } catch (failure: Exception) {
                    stop(permit, "acquire_unknown", failure)
                    throw CouponAdmissionException(CouponAdmissionFailure.UNAVAILABLE, failure)
                }
                if (acquired) {
                    synchronized(monitor) { running[permit] = Execution.RUNNING }
                    granted = true
                    count("accepted")
                    return permit
                }

                synchronized(monitor) {
                    ensureOpen()
                    if (remaining(started) <= 0) throw full("wait_timeout")
                    if (!queued) {
                        if (waiting.get() >= maxWaiters) throw full("waiting_full")
                        waiting.incrementAndGet()
                        queued = true
                        count("waiting")
                    }
                    running[permit] = Execution.WAITING

                    // 반환 알림은 한 대기자를 깨운다. 외부 반환도 확인하도록 주기적 재검사에 jitter를 준다.
                    val delay =
                        minOf(remaining(started), (retryNanos * ThreadLocalRandom.current().nextDouble(0.75, 1.25)).toLong())
                    if (delay <= 0) throw full("wait_timeout")
                    TimeUnit.NANOSECONDS.timedWait(monitor, delay)

                    ensureOpen()
                    if (remaining(started) <= 0) throw full("wait_timeout")
                    running[permit] = Execution.ACQUIRING
                }
            }
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            count("wait_cancelled")
            throw CouponAdmissionException(CouponAdmissionFailure.CANCELLED, failure)
        } finally {
            synchronized(monitor) {
                if (queued) waiting.decrementAndGet()
                if (!granted) running.remove(permit)
            }
        }
    }

    private fun ensureOpen() {
        if (state != State.OPEN) throw CouponAdmissionException(CouponAdmissionFailure.UNAVAILABLE)
    }

    private fun remaining(started: Long) = waitNanos - (System.nanoTime() - started)

    private fun full(outcome: String): CouponAdmissionException {
        count(outcome)
        count("full")
        return CouponAdmissionException(CouponAdmissionFailure.FULL)
    }

    /** 발급 실행 종료가 확인된 경우만 반환한다. 결과 불명·반환 실패는 점유를 유지하고 신규 수용만 닫는다. */
    fun complete(permit: CouponPermit, executionEnded: Boolean) {
        val claimed = synchronized(monitor) {
            if (running[permit] != Execution.RUNNING) {
                false
            } else {
                running[permit] = Execution.COMPLETING
                true
            }
        }
        if (!claimed) return

        try {
            if (!executionEnded) {
                stop(permit, "issuance_unknown")
                return
            }

            try {
                permits.release(permit)
                count("released")
                synchronized(monitor) { monitor.notify() }
            } catch (failure: Exception) {
                stop(permit, "release_failed", failure)
            }
        } finally {
            forget(permit)
        }
    }

    private fun stop(permit: CouponPermit, outcome: String, cause: Throwable? = null) {
        close()
        count(outcome)
        log.error("coupon_admission outcome={} token={}", outcome, permit.token, cause)
    }

    private fun forget(permit: CouponPermit) {
        synchronized(monitor) { running.remove(permit) }
    }

    private fun count(outcome: String) = meters.counter("coupon.admission", "outcome", outcome).increment()
}
