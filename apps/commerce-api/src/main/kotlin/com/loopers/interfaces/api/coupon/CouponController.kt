package com.loopers.interfaces.api.coupon

import com.loopers.application.coupon.CouponApplicationService
import com.loopers.domain.coupon.IssuedCoupon
import com.loopers.interfaces.api.ApiResponse
import com.loopers.interfaces.api.requesterId
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity

data class CouponResponse(
    val couponId: Long,
    val campaignId: Long,
    val userId: Long,
    val issuedAt: Instant,
    val benefitDescription: String,
) {
    companion object {
        fun from(coupon: IssuedCoupon) =
            CouponResponse(coupon.id, coupon.campaignId, coupon.userId, coupon.issuedAt, coupon.benefitDescription)
    }
}

@RestController
class CouponController(private val coupons: CouponApplicationService) {
    @PostMapping("/api/v1/coupon-campaigns/{campaignId}/issues")
    fun issue(
        @RequestHeader("X-USER-ID", required = false) header: String?,
        @PathVariable campaignId: Long,
    ): ResponseEntity<ApiResponse<CouponResponse>> {
        val result = coupons.issue(requesterId(header), campaignId)
        val body = ApiResponse.success(CouponResponse.from(result.coupon))

        return ResponseEntity.status(if (result.created) HttpStatus.CREATED else HttpStatus.OK).body(body)
    }

    @GetMapping("/api/v1/coupons")
    fun owned(@RequestHeader("X-USER-ID", required = false) header: String?): ApiResponse<List<CouponResponse>> =
        ApiResponse.success(coupons.owned(requesterId(header)).map(CouponResponse::from))
}
