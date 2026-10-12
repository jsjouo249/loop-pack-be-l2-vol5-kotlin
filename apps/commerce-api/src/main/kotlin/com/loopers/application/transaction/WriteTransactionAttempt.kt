package com.loopers.application.transaction

import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

class AttemptState {
    var completion: Int = TransactionSynchronization.STATUS_UNKNOWN
        internal set
    var bodyFailure: Throwable? = null
        internal set
}

@Component
class WriteTransactionAttempt {
    // 별도 빈의 프록시를 거쳐 호출해야 매 시도마다 트랜잭션을 시작하고 종료할 수 있다.
    @Transactional(timeout = 5, rollbackFor = [Exception::class])
    fun <T> execute(state: AttemptState, action: () -> T): T {
        // 본문에서 예외가 발생했다는 사실만으로 롤백 성공을 단정하지 않고 실제 종료 상태를 받는다.
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCompletion(status: Int) {
                state.completion = status
            }
        })
        try {
            return action()
        } catch (failure: Throwable) {
            // 이후 커밋·롤백 처리에서 다른 예외가 발생해도 최초 본문 오류를 확인할 수 있게 남긴다.
            state.bodyFailure = failure
            throw failure
        }
    }
}
