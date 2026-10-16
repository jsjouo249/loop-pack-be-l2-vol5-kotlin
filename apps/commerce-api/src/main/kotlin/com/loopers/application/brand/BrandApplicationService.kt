package com.loopers.application.brand

import com.loopers.application.common.PageResult
import com.loopers.application.common.offset
import com.loopers.application.common.pageResult
import com.loopers.application.common.positiveId
import com.loopers.domain.brand.Brand
import com.loopers.domain.brand.BrandRepository
import com.loopers.domain.commerce.CommerceException
import com.loopers.domain.commerce.CommerceFailure
import com.loopers.domain.product.ProductRepository
import com.loopers.application.product.ProductListInvalidation
import com.loopers.application.transaction.RetryingWriteExecutor
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.ZonedDateTime

data class BrandResult(val id: Long, val name: String, val createdAt: ZonedDateTime, val updatedAt: ZonedDateTime)

@Service
class BrandApplicationService(
    private val brands: BrandRepository,
    private val products: ProductRepository,
    private val writes: RetryingWriteExecutor,
    private val invalidation: ProductListInvalidation,
) {
    fun create(name: String): BrandResult = result(brands.create(Brand(name)))

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun get(id: Long): BrandResult {
        positiveId(id)
        return result(brands.findActive(id) ?: throw CommerceException(CommerceFailure.BRAND_NOT_FOUND))
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun list(page: Int, size: Int): PageResult<BrandResult> {
        val offset = offset(page, size)
        return pageResult(page, size, brands.countActive(), brands.findActivePage(offset, size).map(::result))
    }

    fun update(id: Long, name: String): BrandResult = writes.execute("brand.rename") {
        positiveId(id)
        val brand = brands.findActive(id) ?: throw CommerceException(CommerceFailure.BRAND_NOT_FOUND)
        brand.rename(name)
        result(
            brands.updateName(brand) ?: throw CommerceException(CommerceFailure.BRAND_NOT_FOUND),
        ).also { invalidation.afterCommit() }
    }

    /** 상품 등록과 같은 브랜드 잠금을 사용해 삭제 대상 누락을 막고, 연결 상품과 브랜드를 함께 삭제한다. */
    fun delete(id: Long): Unit = writes.execute("brand.delete") {
        positiveId(id)
        val brand = brands.findActiveForUpdate(id) ?: throw CommerceException(CommerceFailure.BRAND_NOT_FOUND)
        // 저장소가 반환한 ID 오름차순으로 잠근다. 그사이 개별 삭제가 끝난 상품은 제외한다.
        val targets = products.findActiveIdsByBrandId(id).mapNotNull(products::findActiveForUpdate)
        // 재고와 무관하게 상품별 도메인 삭제 행동을 수행하고, 중간 실패는 전체 롤백으로 처리한다.
        targets.forEach { it.delete() }
        products.markDeletedAll(targets)
        brand.delete()
        brands.markDeleted(brand)
        invalidation.afterCommit()
    }

    private fun result(brand: Brand) = BrandResult(brand.id, brand.name, brand.createdAt, brand.updatedAt)
}
