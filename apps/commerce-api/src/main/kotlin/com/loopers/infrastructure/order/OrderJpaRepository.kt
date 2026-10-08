package com.loopers.infrastructure.order

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface OrderJpaRepository : JpaRepository<OrderJpaEntity, Long> {
    fun findAllBy(pageable: Pageable): List<OrderJpaEntity>

    fun findByUserId(userId: Long, pageable: Pageable): List<OrderJpaEntity>

    fun countByUserId(userId: Long): Long
}
