package com.loopers.infrastructure.brand

import com.loopers.domain.BaseEntity
import com.loopers.domain.commerce.EntityState
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Index
import jakarta.persistence.Table

@Entity
@Table(name = "brands", indexes = [Index(columnList = "deleted_at,created_at,id")])
class BrandJpaEntity(
    @Column(nullable = false, length = 255)
    var name: String,
) : BaseEntity() {
    fun toEntity(): BrandEntity = BrandEntity(name, EntityState(id, createdAt, updatedAt, deletedAt))
}
