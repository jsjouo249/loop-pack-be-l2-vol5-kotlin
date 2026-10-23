package com.loopers.domain.payment

import com.loopers.domain.commerce.CommerceException
import com.loopers.domain.commerce.CommerceFailure
import java.time.ZonedDateTime

enum class AttemptStatus { REQUESTED, PENDING, UNKNOWN, SUCCESS, FAILED }
enum class ResultEffect { NONE, SUCCESS, FAILED }

class PaymentAttempt(
    val orderId: Long,
    val userId: Long,
    val key: String,
    val amount: Long,
    val providerOrderId: String,
    val account: String = "135135",
    initialStatus: AttemptStatus = AttemptStatus.REQUESTED,
    initialTransactionKey: String? = null,
    initialQuarantined: Boolean = false,
    initialLastCheck: String? = null,
    val requestedAt: ZonedDateTime = ZonedDateTime.now(),
) {
    var status = initialStatus
        private set
    var transactionKey = initialTransactionKey
        private set
    var quarantined = initialQuarantined
        private set
    var lastCheck = initialLastCheck
        private set
    val terminal: Boolean get() = status == AttemptStatus.SUCCESS || status == AttemptStatus.FAILED

    init {
        require(orderId > 0 && userId > 0 && amount >= 0 && providerOrderId.length >= 6)
        validateKey(key)
    }

    fun assertRequest(requestKey: String, requestedAmount: Long) {
        if (requestKey != key || requestedAmount != amount) throw CommerceException(CommerceFailure.PAYMENT_ATTEMPT_CONFLICT)
    }

    fun observe(observation: PaymentObservation): ResultEffect {
        if (observation.account != account) {
            lastCheck = "IDENTITY_MISMATCH"
            if (status == AttemptStatus.REQUESTED) status = AttemptStatus.UNKNOWN
            return ResultEffect.NONE
        }
        val transaction = observation.transaction
        if (transaction != null && (
            transaction.orderId != providerOrderId || transaction.amount != amount ||
            transaction.transactionKey.isBlank() || (transactionKey != null && transactionKey != transaction.transactionKey)
        )
        ) {
            lastCheck = "IDENTITY_MISMATCH"
            if (status == AttemptStatus.REQUESTED) status = AttemptStatus.UNKNOWN
            return ResultEffect.NONE
        }
        if (observation.kind == ObservationKind.CONFLICT) {
            lastCheck = observation.issue ?: "CONFLICT"
            quarantined = quarantined || observation.quarantine
            if (!terminal && quarantined) status = AttemptStatus.UNKNOWN
            return ResultEffect.NONE
        }
        if (quarantined) return ResultEffect.NONE
        if (observation.kind == ObservationKind.REJECTED_NO_EFFECT && transactionKey != null) {
            lastCheck = "TERMINAL_CONFLICT"
            return ResultEffect.NONE
        }
        val next = when (observation.kind) {
            ObservationKind.SUCCESS -> AttemptStatus.SUCCESS
            ObservationKind.FAILED, ObservationKind.REJECTED_NO_EFFECT -> AttemptStatus.FAILED
            else -> null
        }
        if (terminal) {
            lastCheck = if (next != null && next != status) "TERMINAL_CONFLICT" else observation.issue ?: observation.kind.name
            return ResultEffect.NONE
        }
        if (observation.kind in setOf(ObservationKind.SUCCESS, ObservationKind.FAILED, ObservationKind.PENDING)) {
            check(transaction != null)
            transactionKey = transaction.transactionKey
        }
        lastCheck = observation.issue ?: observation.kind.name
        if (next != null) {
            status = next
            return if (next == AttemptStatus.SUCCESS) ResultEffect.SUCCESS else ResultEffect.FAILED
        }
        when (observation.kind) {
            ObservationKind.PENDING -> status = AttemptStatus.PENDING
            ObservationKind.UNKNOWN -> status = AttemptStatus.UNKNOWN
            ObservationKind.NOT_FOUND, ObservationKind.UNAVAILABLE -> {
                if (status == AttemptStatus.REQUESTED) status = AttemptStatus.UNKNOWN
            }
            else -> Unit
        }
        return ResultEffect.NONE
    }

    companion object {
        fun validateKey(key: String) {
            if (!Regex("[A-Za-z0-9_-]{1,128}").matches(key)) throw CommerceException(CommerceFailure.INVALID_REQUEST)
        }
        fun providerOrderId(orderId: Long): String = "LP-ORD-" + orderId.toString().padStart(10, '0')
    }
}

interface PaymentAttemptRepository {
    fun find(orderId: Long): PaymentAttempt?
    fun findForUpdate(orderId: Long): PaymentAttempt?
    fun findByKey(key: String): PaymentAttempt?
    fun findByProviderOrderId(providerOrderId: String): PaymentAttempt?
    fun findAll(orderIds: Collection<Long>): List<PaymentAttempt>
    fun create(attempt: PaymentAttempt)
    fun save(attempt: PaymentAttempt)
    fun recordIssue(attempt: PaymentAttempt, observation: PaymentObservation, reason: String)
}
