package com.loopers.infrastructure.brand

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface BrandJpaRepository : JpaRepository<BrandJpaEntity, Long> {
    fun countByDeletedAtIsNull(): Long

    fun findByIdAndDeletedAtIsNull(id: Long): BrandJpaEntity?

    fun findByIdInAndDeletedAtIsNull(ids: Collection<Long>): List<BrandJpaEntity>

    fun findByDeletedAtIsNull(pageable: Pageable): List<BrandJpaEntity>
}
