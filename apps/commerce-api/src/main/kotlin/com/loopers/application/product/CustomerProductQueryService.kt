package com.loopers.application.product

import com.loopers.application.common.PageResult
import com.loopers.application.common.offset
import com.loopers.application.common.pageResult
import com.loopers.application.common.positiveId
import com.loopers.domain.brand.BrandRepository
import com.loopers.domain.commerce.CommerceException
import com.loopers.domain.commerce.CommerceFailure
import com.loopers.domain.like.ProductLikeRepository
import com.loopers.domain.product.Product
import com.loopers.domain.product.ProductListQuery
import com.loopers.domain.product.ProductListItem
import com.loopers.domain.product.ProductListCache
import com.loopers.domain.product.ProductListCacheRead
import com.loopers.domain.product.ProductListKey
import com.loopers.domain.product.ProductListSnapshot
import io.micrometer.core.instrument.MeterRegistry
import com.loopers.domain.product.ProductRepository
import com.loopers.domain.product.ProductSort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.ZonedDateTime

data class CustomerProductResult(
    val id: Long,
    val name: String,
    val price: Long,
    val stock: Int,
    val brandId: Long,
    val brandName: String,
    val likeCount: Long,
    val createdAt: ZonedDateTime,
)

@Service
class CustomerProductQueryService(
    private val products: ProductRepository,
    private val brands: BrandRepository,
    private val likes: ProductLikeRepository,
    private val lists: ProductListQuery,
    private val cache: ProductListCache,
    private val meters: MeterRegistry,
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun get(id: Long): CustomerProductResult {
        positiveId(id)
        return getMany(listOf(id)).single()
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun getMany(ids: List<Long>): List<CustomerProductResult> {
        if (ids.isEmpty()) return emptyList()
        val found = products.findActiveByIds(ids).associateBy { it.id }
        return results(ids.map { found[it] ?: throw CommerceException(CommerceFailure.PRODUCT_NOT_FOUND) })
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun list(brandId: Long?, page: Int, size: Int, sort: String): PageResult<CustomerProductResult> {
        if (brandId != null) positiveId(brandId)
        val productSort = when (sort) {
            "latest" -> ProductSort.LATEST
            "price_asc" -> ProductSort.PRICE_ASC
            "likes_desc" -> ProductSort.LIKES_DESC
            else -> throw CommerceException(CommerceFailure.UNSUPPORTED_SORT)
        }
        val offset = offset(page, size)
        val key = ProductListKey(brandId, productSort, page, size)
        val cached = cache.read(key)
        if (cached is ProductListCacheRead.Hit) {
            val ids = cached.snapshot.content.map { it.id }
            if (lists.findVisibleIds(ids).containsAll(ids)) return cached.snapshot.toResult()
            meters.counter("commerce.product.list.visibility.rejected").increment()
        }
        meters.counter("commerce.product.list.db.loads").increment()
        val content = lists.findPage(brandId, offset, size, productSort)
        val total = products.countActive(brandId)
        val result = pageResult(page, size, total, content)
        val snapshot = ProductListSnapshot(content, page, size, total, result.totalPages)
        if (cached != ProductListCacheRead.Unavailable) cache.put(key, snapshot)
        return snapshot.toResult()
    }

    private fun results(pageProducts: List<Product>): List<CustomerProductResult> {
        if (pageProducts.isEmpty()) return emptyList()
        val brandById = brands.findActiveByIds(pageProducts.map { it.brandId }.distinct()).associateBy { it.id }
        val likeCounts = likes.countByProductIds(pageProducts.map { it.id })
        return pageProducts.map { product ->
            val brand = brandById[product.brandId] ?: throw CommerceException(CommerceFailure.BRAND_NOT_FOUND)
            CustomerProductResult(
                product.id,
                product.name,
                product.price.amount,
                product.stock.remaining,
                brand.id,
                brand.name,
                likeCounts[product.id] ?: 0,
                product.createdAt,
            )
        }
    }
}

internal fun ProductListItem.toResult() = CustomerProductResult(id, name, price, stock, brandId, brandName, likeCount, createdAt)

private fun ProductListSnapshot.toResult() = PageResult(content.map { it.toResult() }, page, size, totalElements, totalPages)
