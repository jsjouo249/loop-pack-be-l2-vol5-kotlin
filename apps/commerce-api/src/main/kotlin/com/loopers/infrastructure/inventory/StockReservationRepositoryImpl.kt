package com.loopers.infrastructure.inventory

import com.loopers.domain.inventory.StockReservationRepository
import org.springframework.stereotype.Repository

interface StockReservationStorage : StockReservationRepository

@Repository
@org.springframework.context.annotation.Primary
class StockReservationRepositoryImpl(storage: StockReservationStorage) : StockReservationRepository by storage
