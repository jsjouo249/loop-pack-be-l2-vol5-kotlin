package com.loopers.infrastructure.brand

import com.loopers.domain.commerce.EntityState

data class BrandEntity(val name: String, val state: EntityState)

data class BrandNameChange(val id: Long, val name: String)

data class BrandDeletion(val id: Long, val deletedAt: java.time.ZonedDateTime)

interface BrandEntityRepository {
    fun create(entity: BrandEntity): BrandEntity

    fun findActiveForUpdate(id: Long): BrandEntity?

    fun updateName(change: BrandNameChange): BrandEntity?

    fun markDeleted(change: BrandDeletion)

    fun findActive(id: Long): BrandEntity?

    fun findActiveByIds(ids: Collection<Long>): List<BrandEntity>

    fun findActivePage(offset: Int, limit: Int): List<BrandEntity>

    fun countActive(): Long
}
