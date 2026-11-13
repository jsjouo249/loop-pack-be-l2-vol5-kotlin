package com.loopers.infrastructure.coupon

object CouponDataKeys {
    const val META = "coupon:ssot:{issuance}:meta"
    const val EVENTS = "coupon:ssot:{issuance}:events"
    const val GROUP = "coupon-db"
    const val CONSUMER = "local"

    fun campaign(id: Long) = "coupon:ssot:{issuance}:campaign:$id"
    fun owner(id: Long) = "coupon:ssot:{issuance}:owner:$id"
}
