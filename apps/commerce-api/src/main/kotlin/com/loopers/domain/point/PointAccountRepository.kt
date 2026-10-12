package com.loopers.domain.point

interface PointAccountRepository {
    fun findByUserId(userId: Long): PointAccount?

    fun create(account: PointAccount): PointAccount

    fun findByUserIdForUpdate(userId: Long): PointAccount?

    fun saveBalance(account: PointAccount): PointAccount
}
