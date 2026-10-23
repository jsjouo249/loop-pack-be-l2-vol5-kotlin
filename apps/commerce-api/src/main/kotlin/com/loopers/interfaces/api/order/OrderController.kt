package com.loopers.interfaces.api.order

import com.loopers.application.common.PageResult
import com.loopers.application.payment.PaymentInput
import com.loopers.application.order.OrderResult
import com.loopers.domain.order.OrderStatus
import com.loopers.application.order.OrderApplicationService
import com.loopers.application.order.OrderItemInput
import com.loopers.interfaces.api.ApiResponse
import com.loopers.interfaces.api.requesterId
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class OrderItemRequest(val productId: Long, val quantity: Int)

data class CreateOrderRequest(val items: List<OrderItemRequest>)

class ConfirmOrderRequest(val paymentAttemptKey: String? = null, val cardType: String? = null, val cardNo: String? = null) {
    override fun toString() = "ConfirmOrderRequest(redacted)"
}

@RestController
@RequestMapping("/api/v1/orders")
class OrderController(private val orders: OrderApplicationService) {
    @PostMapping
    fun create(
        @RequestHeader("X-USER-ID", required = false) header: String?,
        @RequestBody request: CreateOrderRequest,
    ): ResponseEntity<ApiResponse<OrderResponse>> =
        ResponseEntity.status(HttpStatus.CREATED).body(
            ApiResponse.success(
                OrderResponse.from(
                    orders.create(
                        requesterId(header),
                        request.items.map {
                OrderItemInput(it.productId, it.quantity)
            },
                    ),
                ),
            ),
        )

    @PostMapping("/{orderId}/confirm")
    fun confirm(
        @RequestHeader("X-USER-ID", required = false) header: String?,
        @PathVariable orderId: Long,
        @RequestBody(required = false) request: ConfirmOrderRequest?,
    ): ResponseEntity<ApiResponse<OrderResponse>> = paymentResponse(
        orders.confirm(requesterId(header), orderId, request?.let { PaymentInput(it.paymentAttemptKey, it.cardType, it.cardNo) }),
    )

    @PostMapping("/{orderId}/payment/reconcile")
    fun reconcile(
        @RequestHeader("X-USER-ID", required = false) header: String?,
        @PathVariable orderId: Long,
    ): ResponseEntity<ApiResponse<OrderResponse>> = paymentResponse(orders.reconcile(requesterId(header), orderId))

    private fun paymentResponse(result: OrderResult): ResponseEntity<ApiResponse<OrderResponse>> {
        val status = if (result.status == OrderStatus.PAYMENT_PENDING) HttpStatus.ACCEPTED else HttpStatus.OK
        return ResponseEntity.status(status).header("Location", "/api/v1/orders/${result.id}")
            .body(ApiResponse.success(OrderResponse.from(result)))
    }

    @GetMapping
    fun list(
        @RequestHeader("X-USER-ID", required = false) header: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ApiResponse<PageResult<OrderResponse>> {
        val result = orders.listForUser(requesterId(header), page, size)
        return ApiResponse.success(
            PageResult(
                result.content.map(OrderResponse::from),
                result.page,
                result.size,
                result.totalElements,
                result.totalPages,
            ),
        )
    }

    @GetMapping("/{orderId}")
    fun get(
        @RequestHeader("X-USER-ID", required = false) header: String?,
        @PathVariable orderId: Long,
    ): ApiResponse<OrderResponse> {
        return ApiResponse.success(OrderResponse.from(orders.getForUser(requesterId(header), orderId)))
    }
}
