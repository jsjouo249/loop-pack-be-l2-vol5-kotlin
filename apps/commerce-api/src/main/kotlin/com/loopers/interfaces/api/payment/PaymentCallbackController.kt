package com.loopers.interfaces.api.payment

import com.loopers.application.payment.PaymentApplicationService
import com.loopers.domain.order.OrderStatus
import com.loopers.domain.payment.ProviderStatus
import com.loopers.interfaces.api.ApiResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

data class PaymentCallbackRequest(val providerOrderId: String, val transactionKey: String, val status: ProviderStatus)

@RestController
class PaymentCallbackController(private val payments: PaymentApplicationService) {
    @PostMapping("/api/v1/payments/callback")
    fun callback(@RequestBody request: PaymentCallbackRequest): ResponseEntity<ApiResponse<Map<String, Any>>> {
        if (request.providerOrderId.length !in 6..128 || request.transactionKey.isBlank() ||
            request.transactionKey.length > 128
        ) {
            throw com.loopers.domain.commerce.CommerceException(com.loopers.domain.commerce.CommerceFailure.INVALID_REQUEST)
        }
        val result = payments.callback(request.providerOrderId, request.transactionKey)
        val status = if (result.order.status == OrderStatus.PAYMENT_PENDING) 202 else 200
        return ResponseEntity.status(
            status,
        ).body(ApiResponse.success(mapOf("orderId" to result.order.id, "status" to result.order.status)))
    }
}
