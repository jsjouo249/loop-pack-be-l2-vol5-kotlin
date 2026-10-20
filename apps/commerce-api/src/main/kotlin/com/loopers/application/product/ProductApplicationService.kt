package com.loopers.application.product

import com.loopers.application.common.PageResult
import com.loopers.application.common.offset
import com.loopers.application.common.pageResult
import com.loopers.application.common.positiveId
import com.loopers.domain.brand.BrandRepository
import com.loopers.domain.commerce.CommerceException
import com.loopers.domain.commerce.CommerceFailure
import com.loopers.domain.product.Product
import com.loopers.domain.product.ProductRepository
import com.loopers.application.transaction.RetryingWriteExecutor
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.ZonedDateTime

data class ProductResult(
    val id: Long,
    val name: String,
    val price: Long,
    val stock: Int,
    val brandId: Long,
    val brandName: String,
    val createdAt: ZonedDateTime,
    val updatedAt: ZonedDateTime,
)

@Service
class ProductApplicationService(
    private val products: ProductRepository,
    private val brands: BrandRepository,
    private val writes: RetryingWriteExecutor,
    private val invalidation: ProductListInvalidation,
) {
    fun create(brandId: Long, name: String, price: Long, stock: Int): ProductResult = writes.execute("product.create") {
        positiveId(brandId)
        // 브랜드 삭제와 같은 행을 잠가 등록이 먼저면 삭제 대상에 포함되고, 삭제가 먼저면 등록을 거절한다.
        val brand = brands.findActiveForUpdate(brandId) ?: throw CommerceException(CommerceFailure.BRAND_NOT_FOUND)
        result(products.create(Product(brandId, name, price, stock)), brand.name).also { invalidation.afterCommit() }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun get(id: Long): ProductResult {
        positiveId(id)
        return result(products.findActive(id) ?: throw CommerceException(CommerceFailure.PRODUCT_NOT_FOUND))
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun list(brandId: Long?, page: Int, size: Int): PageResult<ProductResult> {
        if (brandId != null) positiveId(brandId)
        val offset = offset(page, size)
        val pageProducts = products.findActivePage(brandId, offset, size)
        val brandNames = brands.findActiveByIds(pageProducts.map { it.brandId }.distinct()).associate { it.id to it.name }
        val content = pageProducts.map { product ->
            result(product, brandNames[product.brandId] ?: throw CommerceException(CommerceFailure.BRAND_NOT_FOUND))
        }
        return pageResult(page, size, products.countActive(brandId), content)
    }

    fun update(id: Long, name: String, price: Long): ProductResult = writes.execute("product.details") {
        positiveId(id)
        val product = products.findActive(id) ?: throw CommerceException(CommerceFailure.PRODUCT_NOT_FOUND)
        product.changeDetails(name, price)
        // 지정 필드만 갱신하며, 조회 이후 삭제된 경우도 UPDATE의 활성 조건으로 거절한다.
        result(products.updateDetails(product) ?: throw CommerceException(CommerceFailure.PRODUCT_NOT_FOUND)).also {
            invalidation.afterCommit()
        }
    }

    // 관리자는 최종 수량을 지정한다. UPDATE의 쓰기 잠금으로 주문 차감과 순서가 정해진다.
    fun adjustStock(id: Long, stock: Int): ProductResult = writes.execute("product.stock") {
        positiveId(id)
        val product = products.findActive(id) ?: throw CommerceException(CommerceFailure.PRODUCT_NOT_FOUND)
        product.adjustStock(stock)
        result(products.setStock(product) ?: throw CommerceException(CommerceFailure.PRODUCT_NOT_FOUND)).also {
            invalidation.afterCommit()
        }
    }

    fun delete(id: Long): Unit = writes.execute("product.delete") {
        positiveId(id)
        val product = products.findActive(id) ?: throw CommerceException(CommerceFailure.PRODUCT_NOT_FOUND)
        product.delete()
        if (!products.markDeleted(product)) throw CommerceException(CommerceFailure.PRODUCT_NOT_FOUND)
        invalidation.afterCommit()
    }

    private fun result(product: Product): ProductResult =
        result(product, brands.findActive(product.brandId)?.name ?: throw CommerceException(CommerceFailure.BRAND_NOT_FOUND))

    private fun result(product: Product, brandName: String) =
        ProductResult(
            product.id,
            product.name,
            product.price.amount,
            product.stock.remaining,
            product.brandId,
            brandName,
            product.createdAt,
            product.updatedAt,
        )
}
