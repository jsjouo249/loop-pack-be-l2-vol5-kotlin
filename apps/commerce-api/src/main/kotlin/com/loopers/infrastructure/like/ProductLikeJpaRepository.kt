package com.loopers.infrastructure.like

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.jpa.repository.Lock
import jakarta.persistence.LockModeType
import org.springframework.data.repository.query.Param

interface ProductLikeJpaRepository : JpaRepository<ProductLikeJpaEntity, Long> {
    fun countByProductId(productId: Long): Long

    @Query(
        "select p.id as productId, p.likeCount as likeCount from ProductJpaEntity p where p.id in :productIds",
    )
    fun countByProductIds(@Param("productIds") productIds: Collection<Long>): List<ProductLikeCount>

    fun findByUserIdAndProductId(userId: Long, productId: Long): ProductLikeJpaEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from ProductLikeJpaEntity l where l.userId = :userId and l.productId = :productId")
    fun findForUpdate(@Param("userId") userId: Long, @Param("productId") productId: Long): ProductLikeJpaEntity?

    @Query(
        value = "select l.* from product_likes l join products p on p.id = l.product_id " +
            "where l.user_id = :userId and p.deleted_at is null " +
            "order by l.liked_at desc, l.id desc limit :limit offset :offset",
        nativeQuery = true,
    )
    fun findActivePageByUser(
        @Param("userId") userId: Long,
        @Param("offset") offset: Int,
        @Param("limit") limit: Int,
    ): List<ProductLikeJpaEntity>

    @Query(
        value = "select count(*) from product_likes l join products p on p.id = l.product_id " +
            "where l.user_id = :userId and p.deleted_at is null",
        nativeQuery = true,
    )
    fun countActiveByUser(@Param("userId") userId: Long): Long
}

interface ProductLikeCount {
    val productId: Long
    val likeCount: Long
}
