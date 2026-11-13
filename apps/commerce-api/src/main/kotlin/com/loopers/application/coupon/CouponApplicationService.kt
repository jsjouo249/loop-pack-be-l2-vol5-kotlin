package com.loopers.application.coupon

import com.loopers.application.common.positiveId
import com.loopers.domain.commerce.CommerceException
import com.loopers.domain.commerce.CommerceFailure
import com.loopers.domain.coupon.CouponAuthority
import com.loopers.domain.coupon.IssuedCoupon
import com.loopers.domain.user.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronizationManager

data class CouponIssueResult(val coupon: IssuedCoupon, val created: Boolean)

@Service
class CouponApplicationService(
    private val users: UserRepository,
    private val authority: CouponAuthority,
    private val admission: CouponAdmissionService,
) {
    /** 기존 Redis 결과를 먼저 반환하고, 별도 슬롯을 얻은 실행만 Lua로 발급한다. 결과 불명은 점유를 유지한다. */
    fun issue(userId: Long, campaignId: Long): CouponIssueResult {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) { "Coupon issuance requires no outer transaction" }
        validateUser(userId)
        positiveId(campaignId)

        read { authority.find(userId, campaignId) }?.let { return CouponIssueResult(it, false) }

        val permit = admission.acquire()
        var ended = false
        try {
            val result = authority.issue(userId, campaignId, permit)
            ended = true

            return CouponIssueResult(result.coupon, result.created)
        } catch (failure: CommerceException) {
            ended = true
            throw failure
        } catch (failure: Exception) {
            admission.close()
            throw failure
        } finally {
            admission.complete(permit, ended)
        }
    }

    fun owned(userId: Long): List<IssuedCoupon> {
        validateUser(userId)

        return read { authority.owned(userId) }
    }

    private fun <T> read(action: () -> T): T = try {
        action()
    } catch (failure: Exception) {
        admission.close()
        throw failure
    }

    private fun validateUser(userId: Long) {
        positiveId(userId)
        if (users.find(userId) == null) throw CommerceException(CommerceFailure.USER_NOT_FOUND)
    }
}
