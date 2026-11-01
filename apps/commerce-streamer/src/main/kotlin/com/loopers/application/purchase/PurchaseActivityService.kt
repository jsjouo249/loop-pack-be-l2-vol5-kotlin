package com.loopers.application.purchase

import com.loopers.domain.order.event.OrderConfirmed
import com.loopers.domain.purchase.PurchaseActivityRepository
import com.loopers.domain.purchase.PurchaseEventConflict
import com.loopers.domain.purchase.PurchaseReceipt
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.SQLException

@Service
class PurchaseActivityService(private val transaction: PurchaseActivityTransaction) {
    fun record(event: OrderConfirmed) {
        // A failed JPA flush poisons its transaction. Re-query only after that transaction rolls back.
        repeat(3) { attempt ->
            try {
                transaction.record(event)

                return
            } catch (failure: DataIntegrityViolationException) {
                val duplicate = generateSequence<Throwable>(failure) { it.cause }
                    .filterIsInstance<SQLException>().any { it.errorCode == 1062 }

                if (!duplicate || attempt == 2) throw failure
            }
        }
    }
}

@Service
class PurchaseActivityTransaction(private val repository: PurchaseActivityRepository) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun record(event: OrderConfirmed) {
        val previousOrder = repository.findEventOrderId(event.eventId)
        if (previousOrder != null && previousOrder != event.orderId) {
            throw PurchaseEventConflict("eventId=${event.eventId} already belongs to orderId=$previousOrder")
        }

        val receipt = repository.findReceipt(event.orderId)
        if (receipt != null) {
            receipt.assertSameFact(event)
        } else {
            repository.insertReceipt(PurchaseReceipt.from(event))
            repository.insertActivities(event)
        }

        // Remember even alternate IDs of the same fact so reusing an accepted alias is detectable.
        if (previousOrder == null) repository.insertEvent(event.eventId, event.orderId)
    }
}
