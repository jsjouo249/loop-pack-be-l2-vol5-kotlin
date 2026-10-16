package com.loopers.domain.product

data class ProductListKey(val brandId: Long?, val sort: ProductSort, val page: Int, val size: Int)

data class ProductListSnapshot(
    val content: List<ProductListItem>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
)

sealed interface ProductListCacheRead {
    data class Hit(val snapshot: ProductListSnapshot) : ProductListCacheRead
    data object Miss : ProductListCacheRead
    data object Unavailable : ProductListCacheRead
}

/** Best effort storage: Unavailable differs from Miss so a failed read is not followed by a write. */
interface ProductListCache {
    fun read(key: ProductListKey): ProductListCacheRead

    fun put(key: ProductListKey, snapshot: ProductListSnapshot)

    fun evictAll()
}
