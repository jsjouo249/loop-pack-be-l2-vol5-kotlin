package com.loopers.infrastructure.like

import org.springframework.stereotype.Repository
import com.loopers.infrastructure.common.JpaWriteOperations
import jakarta.persistence.EntityManager
import jakarta.persistence.FlushModeType

@Repository
class JpaProductLikeEntityRepository(
    private val jpa: ProductLikeJpaRepository,
    private val writes: JpaWriteOperations,
    private val em: EntityManager,
) : ProductLikeEntityRepository {
    override fun countByProductId(productId: Long): Long = jpa.countByProductId(productId)

    override fun countByProductIds(productIds: Collection<Long>): Map<Long, Long> =
        if (productIds.isEmpty()) emptyMap() else jpa.countByProductIds(productIds).associate { it.productId to it.likeCount }

    override fun findByUserAndProduct(userId: Long, productId: Long): ProductLikeEntity? =
        jpa.findByUserIdAndProductId(userId, productId)?.toEntity()

    override fun createIfAbsent(entity: ProductLikeEntity): Long {
        // application이 먼저 Product를 잠근다. 관계 조회는 이전 RR snapshot을 사용하지 않는다.
        if (jpa.findForUpdate(entity.userId, entity.productId) == null) {
            jpa.saveAndFlush(ProductLikeJpaEntity(entity.userId, entity.productId, entity.likedAt))
            check(writes.update("update products set like_count = like_count + 1 where id = :id", "id" to entity.productId) == 1)
        }
        return currentCount(entity.productId)
    }

    override fun deleteByUserAndProduct(userId: Long, productId: Long): Long {
        writes.flush()
        val removed = writes.update(
            "delete from product_likes where user_id = :userId and product_id = :productId",
            "userId" to userId,
            "productId" to productId,
        )
        if (removed == 1) {
            check(
                writes.update(
                    "update products set like_count = like_count - 1 where id = :id and like_count > 0",
                    "id" to productId,
                ) == 1,
            )
        }
        return currentCount(productId)
    }

    private fun currentCount(productId: Long): Long = (
        em.createNativeQuery("select like_count from products where id = :id for update")
            .setFlushMode(FlushModeType.COMMIT).setParameter("id", productId).singleResult as Number
        ).toLong()

    override fun findActivePageByUser(userId: Long, offset: Int, limit: Int): List<ProductLikeEntity> =
        jpa.findActivePageByUser(userId, offset, limit).map(ProductLikeJpaEntity::toEntity)

    override fun countActiveByUser(userId: Long): Long = jpa.countActiveByUser(userId)
}
