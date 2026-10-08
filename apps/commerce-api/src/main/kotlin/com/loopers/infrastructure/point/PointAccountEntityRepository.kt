package com.loopers.infrastructure.point

data class PointAccountEntity(val userId: Long, val balance: Long)

interface PointAccountEntityRepository {
    fun findByUserId(userId: Long): PointAccountEntity?

    fun create(entity: PointAccountEntity): PointAccountEntity

    fun findByUserIdForUpdate(userId: Long): PointAccountEntity?

    fun saveBalance(entity: PointAccountEntity): PointAccountEntity
}
