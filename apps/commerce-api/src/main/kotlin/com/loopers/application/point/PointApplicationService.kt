package com.loopers.application.point

import com.loopers.application.common.positiveId
import com.loopers.domain.commerce.CommerceException
import com.loopers.domain.commerce.CommerceFailure
import com.loopers.domain.point.PointAccountRepository
import com.loopers.domain.user.UserRepository
import com.loopers.application.transaction.RetryingWriteExecutor
import org.springframework.stereotype.Service

data class PointResult(val userId: Long, val balance: Long)

@Service
class PointApplicationService(
    private val users: UserRepository,
    private val accounts: PointAccountRepository,
    private val writes: RetryingWriteExecutor,
) {
    fun get(userId: Long): PointResult {
        positiveId(userId)
        if (users.find(userId) == null) throw CommerceException(CommerceFailure.USER_NOT_FOUND)
        val account = checkNotNull(accounts.findByUserId(userId)) { "Missing point account: $userId" }
        return PointResult(userId, account.balance.amount)
    }

    // 결제와 같은 포인트 계정 행을 잠가 잔액을 읽고 변경하며, 교착 재시도에서도 잔액을 다시 조회한다.
    fun charge(userId: Long, amount: Long): PointResult = writes.execute("point.charge") {
        positiveId(userId)
        if (users.find(userId) == null) throw CommerceException(CommerceFailure.USER_NOT_FOUND)
        val account = checkNotNull(accounts.findByUserIdForUpdate(userId)) { "Missing point account: $userId" }
        account.charge(amount)
        val saved = accounts.saveBalance(account)
        PointResult(saved.userId, saved.balance.amount)
    }
}
