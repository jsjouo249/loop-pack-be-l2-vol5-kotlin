package com.loopers.infrastructure.product

import com.loopers.domain.commerce.EntityState
import com.loopers.domain.product.ProductSort

data class ProductEntity(
    val brandId: Long,
    val name: String,
    val price: Long,
    val stock: Int,
    val state: EntityState,
)

data class ProductDetailsChange(val id: Long, val name: String, val price: Long)

data class ProductStockChange(val id: Long, val stock: Int)

data class ProductDeletion(val id: Long, val deletedAt: java.time.ZonedDateTime)

interface ProductEntityRepository {
    fun create(entity: ProductEntity): ProductEntity

    fun findActiveForUpdate(id: Long): ProductEntity?

    fun findAnyForUpdate(id: Long): ProductEntity?

    fun findActiveIdsByBrandId(brandId: Long): List<Long>

    fun updateDetails(change: ProductDetailsChange): ProductEntity?

    fun setStock(change: ProductStockChange): ProductEntity?

    fun saveStockChanges(changes: Collection<ProductStockChange>)

    fun restoreReservedStock(changes: Collection<ProductStockChange>)

    fun markDeleted(change: ProductDeletion): Boolean

    fun markDeletedAll(changes: Collection<ProductDeletion>)

    fun findActive(id: Long): ProductEntity?

    fun findAny(id: Long): ProductEntity?

    fun findActiveByIds(ids: Collection<Long>): List<ProductEntity>

    fun existsActiveByBrandId(brandId: Long): Boolean

    fun findActivePage(brandId: Long?, offset: Int, limit: Int, sort: ProductSort): List<ProductEntity>

    fun countActive(brandId: Long?): Long
}
