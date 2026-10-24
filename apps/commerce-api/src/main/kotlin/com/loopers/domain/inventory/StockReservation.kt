package com.loopers.domain.inventory

enum class ReservationStatus { RESERVED, CONSUMED, RELEASED }
class StockReservation(
    val orderId: Long,
    val productId: Long,
    val quantity: Int,
    initialStatus: ReservationStatus = ReservationStatus.RESERVED,
) {
    var status = initialStatus
        private set
    init {
        require(orderId > 0 && productId > 0 && quantity > 0)
    }
    fun consume() {
        check(status == ReservationStatus.RESERVED)
        status = ReservationStatus.CONSUMED
    }
    fun release() {
        check(status == ReservationStatus.RESERVED)
        status = ReservationStatus.RELEASED
    }
}
interface StockReservationRepository {
    fun create(reservations: List<StockReservation>)
    fun findForUpdate(orderId: Long): List<StockReservation>
    fun save(reservations: List<StockReservation>)
}
