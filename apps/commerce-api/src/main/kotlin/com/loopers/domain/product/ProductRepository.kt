package com.loopers.domain.product

interface ProductRepository {
    fun create(product: Product): Product

    fun findActiveForUpdate(id: Long): Product?

    fun findAnyForUpdate(id: Long): Product?

    fun findActiveIdsByBrandId(brandId: Long): List<Long>

    fun updateDetails(product: Product): Product?

    fun setStock(product: Product): Product?

    fun saveStockChanges(products: Collection<Product>)

    fun markDeleted(product: Product): Boolean

    fun markDeletedAll(products: Collection<Product>)

    fun findActive(id: Long): Product?

    fun findAny(id: Long): Product?

    fun findActiveByIds(ids: Collection<Long>): List<Product>

    fun existsActiveByBrandId(brandId: Long): Boolean

    fun findActivePage(brandId: Long?, offset: Int, limit: Int): List<Product>

    fun countActive(brandId: Long?): Long

    fun findActivePage(brandId: Long?, offset: Int, limit: Int, sort: ProductSort): List<Product>
}

enum class ProductSort { LATEST, PRICE_ASC, LIKES_DESC }
