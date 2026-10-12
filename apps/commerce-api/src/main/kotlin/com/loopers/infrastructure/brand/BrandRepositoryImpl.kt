package com.loopers.infrastructure.brand

import com.loopers.domain.brand.Brand
import com.loopers.domain.brand.BrandRepository
import com.loopers.domain.commerce.EntityState
import org.springframework.stereotype.Repository

@Repository
class BrandRepositoryImpl(private val repository: BrandEntityRepository) : BrandRepository {
    override fun create(brand: Brand): Brand = repository.create(brand.toEntity()).toDomain()

    override fun findActiveForUpdate(id: Long): Brand? = repository.findActiveForUpdate(id)?.toDomain()

    override fun updateName(brand: Brand): Brand? = repository.updateName(BrandNameChange(brand.id, brand.name))?.toDomain()

    override fun markDeleted(brand: Brand) = repository.markDeleted(BrandDeletion(brand.id, requireNotNull(brand.deletedAt)))

    override fun findActive(id: Long): Brand? = repository.findActive(id)?.toDomain()

    override fun findActiveByIds(ids: Collection<Long>): List<Brand> =
        repository.findActiveByIds(ids).map { it.toDomain() }

    override fun findActivePage(offset: Int, limit: Int): List<Brand> =
        repository.findActivePage(offset, limit).map { it.toDomain() }

    override fun countActive(): Long = repository.countActive()

    private fun Brand.toEntity() = BrandEntity(name, EntityState(id, createdAt, updatedAt, deletedAt))

    private fun BrandEntity.toDomain() = Brand.reconstitute(name, state)
}
