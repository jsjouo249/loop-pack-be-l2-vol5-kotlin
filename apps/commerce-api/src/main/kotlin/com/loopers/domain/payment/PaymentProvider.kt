package com.loopers.domain.payment

enum class ProviderStatus { PENDING, SUCCESS, FAILED }
enum class ObservationKind { PENDING, SUCCESS, FAILED, UNKNOWN, NOT_FOUND, UNAVAILABLE, REJECTED_NO_EFFECT, CONFLICT }
data class ProviderTransaction(val transactionKey: String, val orderId: String, val amount: Long, val status: ProviderStatus)
data class PaymentObservation(
    val kind: ObservationKind,
    val transaction: ProviderTransaction? = null,
    val issue: String? = null,
    val quarantine: Boolean = false,
    val account: String = "135135",
)

interface PaymentProvider {
    fun create(providerOrderId: String, amount: Long): PaymentObservation
    fun lookup(providerOrderId: String, expectedTransactionKey: String? = null, callback: Boolean = false): PaymentObservation
}
