package com.loopers.application.like

import com.loopers.application.common.PageResult
import com.loopers.application.common.offset
import com.loopers.application.common.pageResult
import com.loopers.application.common.positiveId
import com.loopers.application.product.CustomerProductQueryService
import com.loopers.application.product.CustomerProductResult
import com.loopers.domain.commerce.CommerceException
import com.loopers.domain.commerce.CommerceFailure
import com.loopers.domain.like.ProductLike
import com.loopers.domain.like.ProductLikeRepository
import com.loopers.domain.product.ProductRepository
import com.loopers.domain.user.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.annotation.Isolation
import com.loopers.application.product.ProductListInvalidation
import com.loopers.application.transaction.RetryingWriteExecutor

data class LikeResult(val productId: Long, val liked: Boolean, val likeCount: Long)

@Service
class ProductLikeApplicationService(
    private val likes: ProductLikeRepository,
    private val products: ProductRepository,
    private val users: UserRepository,
    private val customerProducts: CustomerProductQueryService,
    private val writes: RetryingWriteExecutor,
    private val invalidation: ProductListInvalidation,
) {
    fun like(userId: Long, productId: Long): LikeResult = writes.execute("product.like") {
        requireUser(userId)
        positiveId(productId)
        products.findActiveForUpdate(productId) ?: throw CommerceException(CommerceFailure.PRODUCT_NOT_FOUND)
        LikeResult(productId, true, likes.createIfAbsent(ProductLike(userId, productId))).also { invalidation.afterCommit() }
    }

    fun unlike(userId: Long, productId: Long): LikeResult = writes.execute("product.unlike") {
        requireUser(userId)
        positiveId(productId)
        products.findAnyForUpdate(productId) ?: throw CommerceException(CommerceFailure.PRODUCT_NOT_FOUND)
        LikeResult(productId, false, likes.deleteByUserAndProduct(userId, productId)).also { invalidation.afterCommit() }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun list(userId: Long, pathUserId: Long, page: Int, size: Int): PageResult<CustomerProductResult> {
        positiveId(pathUserId)
        if (userId != pathUserId) throw CommerceException(CommerceFailure.USER_MISMATCH)
        requireUser(userId)
        val offset = offset(page, size)
        val content = customerProducts.getMany(likes.findActivePageByUser(userId, offset, size).map { it.productId })
        return pageResult(page, size, likes.countActiveByUser(userId), content)
    }

    private fun requireUser(id: Long) {
        positiveId(id)
        users.find(id) ?: throw CommerceException(CommerceFailure.USER_NOT_FOUND)
    }
}
