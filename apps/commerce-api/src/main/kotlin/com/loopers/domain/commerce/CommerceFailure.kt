package com.loopers.domain.commerce

enum class CommerceFailure(val message: String) {
    PAYMENT_ATTEMPT_CONFLICT("결제 시도 요청이 기존 요청과 다릅니다."),
    PAYMENT_NOT_STARTED("PG 결제 시도가 없습니다."),
    PAYMENT_OBSERVATION_CONFLICT("외부 결제 결과가 기존 사실과 일치하지 않습니다."),
    INVALID_NAME("이름은 1~255자여야 합니다."),
    INVALID_PRICE("가격은 0 이상이어야 합니다."),
    INVALID_STOCK("재고는 0 이상이어야 합니다."),
    INVALID_REQUEST("잘못된 요청입니다."),
    BRAND_NOT_FOUND("브랜드를 찾을 수 없습니다."),
    PRODUCT_NOT_FOUND("상품을 찾을 수 없습니다."),
    USER_NOT_FOUND("사용자를 찾을 수 없습니다."),
    BRAND_HAS_ACTIVE_PRODUCTS("활성 상품이 있는 브랜드는 삭제할 수 없습니다."),
    UNSUPPORTED_SORT("지원하지 않는 정렬 방식입니다."),
    AUTHENTICATION_REQUIRED("사용자 식별이 필요합니다."),
    USER_MISMATCH("요청 사용자와 경로 사용자가 다릅니다."),
    POINT_BALANCE_OVERFLOW("포인트 잔액 범위를 초과합니다."),
    INSUFFICIENT_POINTS("포인트 잔액이 부족합니다."),
    INVALID_QUANTITY("수량은 양수여야 합니다."),
    AMOUNT_OVERFLOW("금액 범위를 초과합니다."),
    ORDER_NOT_FOUND("주문을 찾을 수 없습니다."),
    ORDER_NOT_OWNED("다른 사용자의 주문입니다."),
    ORDER_ALREADY_CONFIRMED("이미 확정된 주문입니다."),
}

class CommerceException(val reason: CommerceFailure) : RuntimeException(reason.message)
