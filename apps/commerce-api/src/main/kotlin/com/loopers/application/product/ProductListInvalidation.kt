package com.loopers.application.product

import com.loopers.domain.product.ProductListCache
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

@Component
class ProductListInvalidation(private val cache: ProductListCache) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun afterCommit() {
        check(TransactionSynchronizationManager.isActualTransactionActive())
        check(!TransactionSynchronizationManager.isCurrentTransactionReadOnly())
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() {
                try {
                    cache.evictAll()
                } catch (failure: Exception) {
                    // DB already committed: never turn a cache failure into a business failure or write retry.
                    log.warn("product_list_cache afterCommit invalidation failed", failure)
                }
            }
        })
    }
}
