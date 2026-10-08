package com.loopers.application.transaction

import com.loopers.domain.commerce.CommerceException
import com.loopers.domain.product.InsufficientStockException
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.SQLException
import java.util.UUID

class TransactionBusyException(cause: Throwable) : RuntimeException("Transaction busy", cause)

@Component
class RetryingWriteExecutor(
    private val attempt: WriteTransactionAttempt,
    private val time: RetryTime,
    private val observer: WriteObserver,
) {
    /**
     * 유스케이스 전체를 별도 트랜잭션으로 실행하고, 전체 롤백이 확인된 교착만 한 번 재시도한다.
     * action 내부에서 상태를 다시 조회해야 재시도 시 앞선 시도의 도메인 객체를 재사용하지 않는다.
     */
    fun <T> execute(operation: String, action: () -> T): T {
        // 외부 트랜잭션에 참여하면 시도별 롤백과 자원 반환을 보장할 수 없다.
        check(!TransactionSynchronizationManager.isActualTransactionActive()) { "Write executor requires no outer transaction" }
        val started = time.nanoTime()
        // 내부 시도와 최종 요청 로그를 연결하는 식별자이며, 요청 중복을 막는 키는 아니다.
        val requestId = UUID.randomUUID().toString()
        var number = 0
        try {
            while (true) {
                number++
                val state = AttemptState()
                try {
                    val result = attempt.execute(state, action)
                    observer.attempt(requestId, operation, number, "SUCCESS")
                    observer.request(requestId, operation, number, "SUCCESS")
                    return result
                } catch (failure: Exception) {
                    // Spring이 최초 SQL 예외를 롤백 예외로 대체할 수 있어 원인도 함께 보존한다.
                    // 원인이 교착이어도 전체 롤백을 확인하지 못했다면 재시도하지 않는다.
                    state.bodyFailure?.takeIf { it !== failure && it !== failure.cause }?.let(failure::addSuppressed)
                    val outcome = classify(failure, state)
                    observer.attempt(requestId, operation, number, outcome)
                    if (outcome != "DEADLOCK" && outcome != "LOCK_TIMEOUT") throw failure
                    // 15초 예산에서 대기 50ms + 연결 획득 3초 + 다음 트랜잭션 5초를 확보한다.
                    if (outcome == "LOCK_TIMEOUT" || number == 2 || remaining(started) < 8_050_000_000L) {
                        throw TransactionBusyException(failure)
                    }
                    // 트랜잭션 프록시가 종료되고 연결 등 자원이 반환된 뒤에만 대기한다.
                    check(!TransactionSynchronizationManager.isActualTransactionActive())
                    check(TransactionSynchronizationManager.getResourceMap().isEmpty()) { "Transaction resources not released" }
                    try {
                        time.sleep(50)
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw IllegalStateException("Write retry interrupted", interrupted)
                    }
                    // 실제 대기는 더 길어질 수 있으므로 깨어난 뒤 추가 시도의 진입 예산을 재확인한다.
                    if (remaining(started) < 8_000_000_000L) throw TransactionBusyException(failure)
                }
            }
        } catch (failure: Exception) {
            // 내부 시도의 실패 횟수와 원 요청의 최종 결과는 별도로 기록한다.
            observer.request(requestId, operation, number, if (isBusiness(failure)) "BUSINESS_REJECTION" else "TECHNICAL_FAILURE")
            throw failure
        }
    }

    // 단조 시계로 추가 시도 진입 여부만 판단하며, HTTP 전체 응답 시간의 상한을 뜻하지 않는다.
    private fun remaining(started: Long): Long = 15_000_000_000L - (time.nanoTime() - started)

    /** 트랜잭션 종료 상태를 먼저 확인한 뒤 업무 거절과 재시도 가능한 SQL 오류를 구분한다. */
    private fun classify(failure: Throwable, state: AttemptState): String {
        if (state.completion != TransactionSynchronization.STATUS_ROLLED_BACK || state.bodyFailure == null) {
            return "UNKNOWN_COMPLETION"
        }
        if (isBusiness(failure)) return "BUSINESS_REJECTION"
        val causes = generateSequence(failure) { it.cause }.take(32).toList()
        // 롤백·연결 장애가 섞이면 하위 SQL 원인이 교착이어도 안전한 재시도로 분류하지 않는다.
        if (causes.any { it is org.springframework.transaction.TransactionSystemException } ||
            causes.filterIsInstance<SQLException>().any { it.sqlState?.startsWith("08") == true }
        ) {
            return "UNKNOWN_COMPLETION"
        }
        val sql = causes.filterIsInstance<SQLException>()
        if (sql.any { it.errorCode == 1213 && it.sqlState == "40001" }) return "DEADLOCK"
        // Connector/J는 서버의 1205/HY000을 1205/40001로 변환할 수 있어 오류 번호도 함께 본다.
        if (sql.any { it.errorCode == 1205 && it.sqlState in setOf("HY000", "40001") }) return "LOCK_TIMEOUT"
        return "TECHNICAL_FAILURE"
    }

    private fun isBusiness(failure: Throwable): Boolean =
        failure is CommerceException || failure is InsufficientStockException
}
