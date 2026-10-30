package com.loopers

import com.loopers.application.order.OrderApplicationService
import com.loopers.application.payment.PaymentInput
import com.loopers.domain.payment.ObservationKind
import com.loopers.domain.payment.PaymentObservation
import com.loopers.domain.payment.PaymentProvider
import com.loopers.domain.payment.ProviderStatus
import com.loopers.domain.payment.ProviderTransaction
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

fun testPaymentInput(orderId: Long) = PaymentInput("order-$orderId", "TEST", "0000-0000-0000-0000")
fun testPaymentJson(orderId: Long) = """{"paymentAttemptKey":"order-$orderId","cardType":"TEST","cardNo":"0000-0000-0000-0000"}"""
fun OrderApplicationService.confirmWithTestCard(userId: Long, orderId: Long) = confirm(userId, orderId, testPaymentInput(orderId))

class ControlledPaymentProvider : PaymentProvider {
    @Volatile var delegate: PaymentProvider = SuccessPaymentProvider()
    override fun create(providerOrderId: String, amount: Long) = delegate.create(providerOrderId, amount)
    override fun lookup(
        providerOrderId: String,
        expectedTransactionKey: String?,
        callback: Boolean,
    ) = delegate.lookup(providerOrderId, expectedTransactionKey, callback)
}
class SuccessPaymentProvider : PaymentProvider {
    override fun create(providerOrderId: String, amount: Long) = PaymentObservation(
        ObservationKind.SUCCESS,
        ProviderTransaction("TX-$providerOrderId", providerOrderId, amount, ProviderStatus.SUCCESS),
    )
    override fun lookup(
        providerOrderId: String,
        expectedTransactionKey: String?,
        callback: Boolean,
    ) = error("Unexpected lookup in regression fixture")
}

@TestConfiguration
class PaymentTestConfiguration {
    @Bean @Primary
    fun testOrderConfirmedPublisher(): com.loopers.domain.order.event.OrderConfirmedPublisher = org.mockito.kotlin.mock()

    @Bean @Primary
    fun controlledPaymentProvider() = ControlledPaymentProvider()
}
