package com.loopers.infrastructure.product

import com.loopers.domain.product.ProductSort
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ProductJpaRepository : JpaRepository<ProductJpaEntity, Long> {
    @Query("select p.id from ProductJpaEntity p where p.brandId = :brandId and p.deletedAt is null order by p.id")
    fun findActiveIdsByBrandId(@Param("brandId") brandId: Long): List<Long>

    fun findByIdAndDeletedAtIsNull(id: Long): ProductJpaEntity?

    fun findByIdInAndDeletedAtIsNull(ids: Collection<Long>): List<ProductJpaEntity>

    fun existsByBrandIdAndDeletedAtIsNull(brandId: Long): Boolean

    @Query("select p.id from ProductJpaEntity p where p.deletedAt is null")
    fun findActivePageIds(pageable: Pageable): List<Long>

    @Query("select p.id from ProductJpaEntity p where p.deletedAt is null and p.brandId = :brandId")
    fun findActivePageIdsByBrandId(@Param("brandId") brandId: Long, pageable: Pageable): List<Long>

    fun countByDeletedAtIsNull(): Long

    fun countByBrandIdAndDeletedAtIsNull(brandId: Long): Long
}

internal fun ProductJpaRepository.activePageIds(
    brandId: Long?,
    offset: Int,
    limit: Int,
    sort: ProductSort,
): List<Long> {
    val ordering = when (sort) {
        ProductSort.PRICE_ASC -> Sort.by(
            Sort.Order.asc("price"),
            Sort.Order.desc("id"),
        )
        ProductSort.LIKES_DESC -> Sort.by(
            Sort.Order.desc("likeCount"),
            Sort.Order.desc("id"),
        )
        ProductSort.LATEST -> Sort.by(
            Sort.Order.desc("createdAt"),
            Sort.Order.desc("id"),
        )
    }
    val pageable = PageRequest.of(offset / limit, limit, ordering)
    return if (brandId == null) findActivePageIds(pageable) else findActivePageIdsByBrandId(brandId, pageable)
}
