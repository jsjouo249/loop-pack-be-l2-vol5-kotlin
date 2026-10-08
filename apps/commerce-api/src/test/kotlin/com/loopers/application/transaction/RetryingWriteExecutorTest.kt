package com.loopers.application.transaction

import com.loopers.domain.commerce.CommerceException
import com.loopers.domain.commerce.CommerceFailure
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.transaction.support.TransactionSynchronization
import java.sql.SQLException

class RetryingWriteExecutorTest {
    private val events = mutableListOf<String>()
    private var now = 0L
    private val time = object : RetryTime() {
        override fun nanoTime(): Long = now
        override fun sleep(millis: Long) {
            events.add("sleep:$millis")
            now += millis * 1_000_000
        }
    }
    private val observer = object : WriteObserver() {
        override fun attempt(requestId: String, operation: String, number: Int, outcome: String) {
            events.add("attempt:$number:$outcome")
        }
        override fun request(requestId: String, operation: String, attempts: Int, outcome: String) {
            events.add("request:$attempts:$outcome")
        }
    }
    private val attempt = object : WriteTransactionAttempt() {
        override fun <T> execute(state: AttemptState, action: () -> T): T {
            events.add("begin")
            try {
                return action()
            } catch (failure: Throwable) {
                state.bodyFailure = failure
                state.completion = TransactionSynchronization.STATUS_ROLLED_BACK
                throw failure
            } finally {
                events.add("released")
            }
        }
    }
    private val executor = RetryingWriteExecutor(attempt, time, observer)

    @Test
    fun `deadlock retries whole callback only after release and requests fixed fifty milliseconds`() {
        var calls = 0
        assertThat(
            executor.execute("test") {
            if (++calls == 1) throw SQLException("deadlock", "40001", 1213)
            42
        },
        ).isEqualTo(42)
        assertThat(events).containsExactly(
            "begin",
            "released",
            "attempt:1:DEADLOCK",
            "sleep:50",
            "begin",
            "released",
            "attempt:2:SUCCESS",
            "request:2:SUCCESS",
        )
    }

    @Test
    fun `two deadlocks exhaust and timeout never retries`() {
        assertThrows<TransactionBusyException> {
            executor.execute("test") { throw SQLException("deadlock", "40001", 1213) }
        }
        assertThat(events.count { it == "begin" }).isEqualTo(2)
        assertThat(events.last()).isEqualTo("request:2:TECHNICAL_FAILURE")
        events.clear()
        assertThrows<TransactionBusyException> {
            executor.execute("test") { throw SQLException("timeout", "HY000", 1205) }
        }
        assertThat(events).containsExactly("begin", "released", "attempt:1:LOCK_TIMEOUT", "request:1:TECHNICAL_FAILURE")
    }

    @Test
    fun `retry business refusal remains original business error`() {
        var calls = 0
        val failure = assertThrows<CommerceException> {
            executor.execute("test") {
                if (++calls == 1) throw SQLException("deadlock", "40001", 1213)
                throw CommerceException(CommerceFailure.INSUFFICIENT_POINTS)
            }
        }
        assertThat(failure.reason).isEqualTo(CommerceFailure.INSUFFICIENT_POINTS)
        assertThat(events.last()).isEqualTo("request:2:BUSINESS_REJECTION")
    }

    @Test
    fun `budget reserves connection transaction and wait before another attempt`() {
        assertThrows<TransactionBusyException> {
            executor.execute("test") {
                now += 7_000_000_000
                throw SQLException("deadlock", "40001", 1213)
            }
        }
        assertThat(events).doesNotContain("sleep:50")
        assertThat(events.count { it == "begin" }).isEqualTo(1)
    }

    @Test
    fun `unknown completion and integrity faults are not retryable`() {
        val unknown = object : WriteTransactionAttempt() {
            override fun <T> execute(state: AttemptState, action: () -> T): T {
                state.completion = TransactionSynchronization.STATUS_UNKNOWN
                throw SQLException("connection lost at commit", "08007", 0)
            }
        }
        assertThrows<SQLException> { RetryingWriteExecutor(unknown, time, observer).execute("test") { 1 } }
        assertThat(events).containsExactly("attempt:1:UNKNOWN_COMPLETION", "request:1:TECHNICAL_FAILURE")
        events.clear()
        assertThrows<SQLException> { executor.execute("test") { throw SQLException("duplicate", "23000", 1062) } }
        assertThat(events.count { it == "begin" }).isEqualTo(1)
    }

    @Test
    fun `budget is checked again after sleep and interrupt prevents another attempt`() {
        val delayed = object : RetryTime() {
            override fun nanoTime(): Long = now
            override fun sleep(millis: Long) {
                now += 8_000_000_000L
            }
        }
        assertThrows<TransactionBusyException> {
            RetryingWriteExecutor(attempt, delayed, observer).execute("test") { throw SQLException("deadlock", "40001", 1213) }
        }
        assertThat(events.count { it == "begin" }).isEqualTo(1)
        events.clear()
        val interrupted = object : RetryTime() {
            override fun nanoTime(): Long = 0
            override fun sleep(millis: Long) {
                throw InterruptedException()
            }
        }
        try {
            assertThrows<IllegalStateException> {
                RetryingWriteExecutor(attempt, interrupted, observer).execute("test") { throw SQLException("deadlock", "40001", 1213) }
            }
            assertThat(Thread.currentThread().isInterrupted).isTrue()
            assertThat(events.count { it == "begin" }).isEqualTo(1)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `outer transaction is rejected before any attempt`() {
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true)
        try {
            assertThrows<IllegalStateException> { executor.execute("test") { 1 } }
            assertThat(events).isEmpty()
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clear()
        }
    }

    @Test
    fun `rollback failure and unconfirmed rollback cannot inherit nested deadlock retryability`() {
        val failedRollback = object : WriteTransactionAttempt() {
            override fun <T> execute(state: AttemptState, action: () -> T): T {
                val sql = SQLException("deadlock", "40001", 1213)
                state.bodyFailure = sql
                state.completion = TransactionSynchronization.STATUS_UNKNOWN
                throw org.springframework.transaction.TransactionSystemException("rollback failed", sql)
            }
        }
        assertThrows<org.springframework.transaction.TransactionSystemException> {
            RetryingWriteExecutor(failedRollback, time, observer).execute("test") { 1 }
        }
        assertThat(events).containsExactly("attempt:1:UNKNOWN_COMPLETION", "request:1:TECHNICAL_FAILURE")
        events.clear()
        assertThrows<SQLException> { executor.execute("test") { throw SQLException("not a MySQL deadlock", "HY000", 1213) } }
        assertThat(events.count { it == "begin" }).isEqualTo(1)
    }

    @Test
    fun `connector j lock timeout uses serialization SQL state but is never retried`() {
        assertThrows<TransactionBusyException> {
            executor.execute("test") { throw SQLException("driver lock timeout", "40001", 1205) }
        }
        assertThat(events).containsExactly("begin", "released", "attempt:1:LOCK_TIMEOUT", "request:1:TECHNICAL_FAILURE")
    }

    @Test
    fun `rollback error retains original statement error without becoming retryable`() {
        val original = java.sql.SQLTimeoutException("statement timed out")
        val rollback = IllegalStateException("rollback connection closed")
        val failed = object : WriteTransactionAttempt() {
            override fun <T> execute(state: AttemptState, action: () -> T): T {
                state.bodyFailure = original
                state.completion = TransactionSynchronization.STATUS_UNKNOWN
                throw rollback
            }
        }
        val actual = assertThrows<IllegalStateException> {
            RetryingWriteExecutor(failed, time, observer).execute("test") { 1 }
        }
        assertThat(actual).isSameAs(rollback)
        assertThat(actual.suppressed).containsExactly(original)
        assertThat(events).containsExactly("attempt:1:UNKNOWN_COMPLETION", "request:1:TECHNICAL_FAILURE")
    }
}
