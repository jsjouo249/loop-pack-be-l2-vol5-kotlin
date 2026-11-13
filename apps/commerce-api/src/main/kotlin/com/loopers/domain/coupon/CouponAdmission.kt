package com.loopers.domain.coupon

enum class CouponAdmissionFailure { FULL, UNAVAILABLE, CANCELLED }

class CouponAdmissionException(
    val reason: CouponAdmissionFailure,
    cause: Throwable? = null,
) : RuntimeException(reason.name, cause)

data class CouponPermit(val token: String, val primaryId: String) {
    init {
        require(token.isNotBlank() && primaryId.isNotBlank())
    }
}

interface CouponPermits {
    fun initializeAfterQuiescence(): String
    fun acquire(permit: CouponPermit, capacity: Int): Boolean
    fun release(permit: CouponPermit)
}
