package com.loopers.domain.like

interface ProductLikeRepository {
    fun countByProductId(productId: Long): Long

    fun countByProductIds(productIds: Collection<Long>): Map<Long, Long>

    fun findByUserAndProduct(userId: Long, productId: Long): ProductLike?

    /** 상품 잠금 뒤 관계를 current read로 확인하고, 신규 관계만 생성한다. 현재 집계값을 반환한다. */
    fun createIfAbsent(like: ProductLike): Long

    fun deleteByUserAndProduct(userId: Long, productId: Long): Long

    fun findActivePageByUser(userId: Long, offset: Int, limit: Int): List<ProductLike>

    fun countActiveByUser(userId: Long): Long
}
