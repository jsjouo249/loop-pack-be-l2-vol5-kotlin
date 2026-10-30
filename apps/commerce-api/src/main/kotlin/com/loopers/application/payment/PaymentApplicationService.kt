package com.loopers.application.payment

import com.loopers.application.common.positiveId
import com.loopers.application.product.ProductListInvalidation
import com.loopers.application.transaction.RetryingWriteExecutor
import com.loopers.domain.commerce.CommerceException
import com.loopers.domain.commerce.CommerceFailure
import com.loopers.domain.inventory.StockReservation
import com.loopers.domain.inventory.StockReservationRepository
import com.loopers.domain.inventory.ReservationStatus
import com.loopers.domain.order.Order
import com.loopers.domain.order.OrderStatus
import com.loopers.domain.order.OrderRepository
import com.loopers.domain.order.PaymentMethod
import com.loopers.domain.order.event.OrderConfirmed
import com.loopers.domain.order.event.PurchasedItem
import com.loopers.domain.payment.PaymentAttempt
import com.loopers.domain.payment.PaymentAttemptRepository
import com.loopers.domain.payment.PaymentObservation
import com.loopers.domain.payment.ObservationKind
import com.loopers.domain.payment.PaymentProvider
import com.loopers.domain.payment.ResultEffect
import com.loopers.domain.product.ProductRepository
import com.loopers.domain.user.UserRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.context.ApplicationEventPublisher
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronizationManager

/** Only the fixed synthetic input is accepted. No card is retained or printed. */
class PaymentInput(val paymentAttemptKey: String?, private val cardType: String?, private val cardNo: String?) {
    fun validatedKey(): String {
        val key = paymentAttemptKey ?: throw CommerceException(CommerceFailure.INVALID_REQUEST)
        PaymentAttempt.validateKey(key)
        if (cardType != "TEST" || cardNo != "0000-0000-0000-0000") throw CommerceException(CommerceFailure.INVALID_REQUEST)
        return key
    }
    override fun toString() = "PaymentInput(redacted)"
}

data class PaymentSnapshot(val order: Order, val attempt: PaymentAttempt?)
private data class PreparedPayment(val snapshot: PaymentSnapshot, val send: Boolean)

@Service
class PaymentReadService(
    private val orders: OrderRepository,
    private val attempts: PaymentAttemptRepository,
    private val users: UserRepository,
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun owned(userId: Long, orderId: Long): PaymentSnapshot {
        positiveId(userId)
        positiveId(orderId)
        users.find(userId) ?: throw CommerceException(CommerceFailure.USER_NOT_FOUND)
        val order = orders.find(orderId) ?: throw CommerceException(CommerceFailure.ORDER_NOT_FOUND)
        if (order.userId != userId) throw CommerceException(CommerceFailure.ORDER_NOT_OWNED)
        return PaymentSnapshot(order, attempts.find(orderId))
    }

    @Transactional(readOnly = true)
    fun callback(providerOrderId: String): PaymentAttempt = attempts.findByProviderOrderId(providerOrderId)
        ?: throw CommerceException(CommerceFailure.PAYMENT_NOT_STARTED)
}

@Service
class PaymentApplicationService(
    private val orders: OrderRepository,
    private val attempts: PaymentAttemptRepository,
    private val reservations: StockReservationRepository,
    private val products: ProductRepository,
    private val users: UserRepository,
    private val provider: PaymentProvider,
    private val writes: RetryingWriteExecutor,
    private val invalidation: ProductListInvalidation,
    private val reads: PaymentReadService,
    private val events: ApplicationEventPublisher,
) {
    fun confirm(userId: Long, orderId: Long, input: PaymentInput?): PaymentSnapshot {
        val prepared = try {
            writes.execute("payment.prepare") {
                positiveId(userId)
                positiveId(orderId)
                users.find(userId) ?: throw CommerceException(CommerceFailure.USER_NOT_FOUND)
                val order = orders.findForUpdate(orderId) ?: throw CommerceException(CommerceFailure.ORDER_NOT_FOUND)
                if (order.userId != userId) throw CommerceException(CommerceFailure.ORDER_NOT_OWNED)
                if (order.paymentMethod == PaymentMethod.POINT) throw CommerceException(CommerceFailure.ORDER_ALREADY_CONFIRMED)
                val key = input?.validatedKey() ?: throw CommerceException(CommerceFailure.INVALID_REQUEST)
                // The locked order proves whether an attempt exists. Avoid FOR UPDATE on a missing PK:
                // InnoDB gap locks would make unrelated first attempts deadlock on insertion.
                val existing = if (order.status == OrderStatus.DRAFT) null else attempts.findForUpdate(orderId)
                if (existing != null) {
                    existing.assertRequest(key, order.totalAmount.amount)
                    return@execute PreparedPayment(PaymentSnapshot(order, existing), false)
                }
                order.assertDraft()
                if (attempts.findByKey(key) != null) throw CommerceException(CommerceFailure.PAYMENT_ATTEMPT_CONFLICT)
                val found = order.items.map {
                    products.findActiveForUpdate(it.productId) ?: throw CommerceException(CommerceFailure.PRODUCT_NOT_FOUND)
                }
                order.items.zip(found).forEach { (item, product) -> product.assertCanDecrease(item.quantity) }
                val attempt =
                    PaymentAttempt(order.id, userId, key, order.totalAmount.amount, PaymentAttempt.providerOrderId(order.id))
                attempts.create(attempt)
                val held = order.items.map { StockReservation(order.id, it.productId, it.quantity) }
                reservations.create(held)
                order.items.zip(found).forEach { (item, product) -> product.decreaseStock(item.quantity) }
                products.saveStockChanges(found)
                order.startPayment()
                val saved = orders.savePaymentState(order)
                invalidation.afterCommit()
                PreparedPayment(PaymentSnapshot(saved, attempt), true)
            }
        } catch (failure: DataIntegrityViolationException) {
            // A simultaneous different order may have committed the same global key. Inspect only after rollback.
            val key = input?.paymentAttemptKey
            if (key != null && attempts.findByKey(key) != null) throw CommerceException(CommerceFailure.PAYMENT_ATTEMPT_CONFLICT)
            throw failure
        }
        if (!prepared.send) return prepared.snapshot
        check(!TransactionSynchronizationManager.isActualTransactionActive())
        val attempt = checkNotNull(prepared.snapshot.attempt)
        // Only HTTP observations are mapped by the adapter. A failure in apply must propagate as a DB failure.
        val observation = provider.create(attempt.providerOrderId, attempt.amount)
        return apply(orderId, observation)
    }

    fun reconcile(userId: Long, orderId: Long): PaymentSnapshot {
        val snapshot = reads.owned(userId, orderId)
        val attempt = snapshot.attempt ?: throw CommerceException(CommerceFailure.PAYMENT_NOT_STARTED)
        if (attempt.terminal || attempt.quarantined) return snapshot
        check(!TransactionSynchronizationManager.isActualTransactionActive())
        return apply(orderId, provider.lookup(attempt.providerOrderId, attempt.transactionKey))
    }

    fun callback(providerOrderId: String, transactionKey: String): PaymentSnapshot {
        val attempt = reads.callback(providerOrderId)
        check(!TransactionSynchronizationManager.isActualTransactionActive())
        val observation = provider.lookup(providerOrderId, transactionKey, callback = true)
        val result = apply(attempt.orderId, observation)
        if (observation.kind == ObservationKind.CONFLICT ||
            result.attempt?.lastCheck in setOf("IDENTITY_MISMATCH", "TERMINAL_CONFLICT")
        ) {
            throw CommerceException(CommerceFailure.PAYMENT_OBSERVATION_CONFLICT)
        }
        return result
    }

    private fun apply(orderId: Long, observation: PaymentObservation): PaymentSnapshot = writes.execute("payment.apply") {
        val order = orders.findForUpdate(orderId) ?: throw CommerceException(CommerceFailure.ORDER_NOT_FOUND)
        val attempt = checkNotNull(attempts.findForUpdate(orderId))
        val held = reservations.findForUpdate(orderId)
        check(
            held.size == order.items.size && held.zip(order.items).all { (r, item) ->
                r.productId == item.productId && r.quantity == item.quantity
            },
        )
        val effect = attempt.observe(observation)
        if (effect != ResultEffect.NONE) {
            check(held.all { it.status == ReservationStatus.RESERVED })
            if (effect == ResultEffect.FAILED) {
                val found = held.map { checkNotNull(products.findAnyForUpdate(it.productId)) }
                held.zip(found).forEach { (r, product) -> product.releaseReservation(r.quantity) }
                products.restoreReservedStock(found)
                held.forEach { it.release() }
                invalidation.afterCommit()
            } else {
                held.forEach { it.consume() }
            }
            reservations.save(held)
            order.finishPayment(effect == ResultEffect.SUCCESS)
            orders.savePaymentState(order)

            if (effect == ResultEffect.SUCCESS) {
                events.publishEvent(
                    OrderConfirmed(
                        OrderConfirmed.stableEventId(order.id),
                        order.id,
                        checkNotNull(order.confirmedAt).toInstant(),
                        order.items.map { PurchasedItem(it.productId, it.quantity) },
                        schemaVersion = 1,
                    ),
                )
            }
        }
        if (observation.kind == ObservationKind.CONFLICT ||
            attempt.lastCheck in setOf("IDENTITY_MISMATCH", "TERMINAL_CONFLICT")
        ) {
            attempts.recordIssue(attempt, observation, attempt.lastCheck ?: "CONFLICT")
        }
        attempts.save(attempt)
        PaymentSnapshot(order, attempt)
    }
}
