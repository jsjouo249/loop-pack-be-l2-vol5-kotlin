package com.loopers.infrastructure.like

import com.loopers.domain.like.ProductLike
import com.loopers.domain.like.ProductLikeRepository
import org.springframework.stereotype.Repository

@Repository
class ProductLikeRepositoryImpl(private val repository: ProductLikeEntityRepository) : ProductLikeRepository {
    override fun countByProductId(productId: Long): Long = repository.countByProductId(productId)

    override fun countByProductIds(productIds: Collection<Long>): Map<Long, Long> = repository.countByProductIds(productIds)

    override fun findByUserAndProduct(userId: Long, productId: Long): ProductLike? =
        repository.findByUserAndProduct(userId, productId)?.toDomain()

    override fun createIfAbsent(like: ProductLike): Long =
        repository.createIfAbsent(ProductLikeEntity(like.userId, like.productId, like.id, like.likedAt))

    override fun deleteByUserAndProduct(userId: Long, productId: Long): Long =
        repository.deleteByUserAndProduct(userId, productId)

    override fun findActivePageByUser(userId: Long, offset: Int, limit: Int): List<ProductLike> =
        repository.findActivePageByUser(userId, offset, limit).map { it.toDomain() }

    override fun countActiveByUser(userId: Long): Long = repository.countActiveByUser(userId)

    private fun ProductLikeEntity.toDomain() = ProductLike(userId, productId, id, likedAt)
}
