package com.loopers.domain.product

import java.time.ZonedDateTime

/** Read data, independent of aggregate state and transport/serialization types. */
data class ProductListItem(
    val id: Long,
    val name: String,
    val price: Long,
    val stock: Int,
    val brandId: Long,
    val brandName: String,
    val likeCount: Long,
    val createdAt: ZonedDateTime,
)

interface ProductListQuery {
    fun findPage(brandId: Long?, offset: Int, limit: Int, sort: ProductSort): List<ProductListItem>

    fun findVisibleIds(ids: Collection<Long>): Set<Long>
}
