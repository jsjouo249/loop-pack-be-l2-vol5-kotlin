package com.loopers.domain.brand

interface BrandRepository {
    fun create(brand: Brand): Brand

    fun findActiveForUpdate(id: Long): Brand?

    fun updateName(brand: Brand): Brand?

    fun markDeleted(brand: Brand)

    fun findActive(id: Long): Brand?

    fun findActiveByIds(ids: Collection<Long>): List<Brand>

    fun findActivePage(offset: Int, limit: Int): List<Brand>

    fun countActive(): Long
}
