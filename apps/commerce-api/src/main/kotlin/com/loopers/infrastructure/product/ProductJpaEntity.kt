package com.loopers.infrastructure.product

import com.loopers.domain.BaseEntity
import com.loopers.domain.commerce.EntityState
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.hibernate.annotations.Check
import org.hibernate.annotations.ColumnDefault

@Entity
@Check(constraints = "price >= 0 and stock >= 0 and like_count >= 0")
@Table(
    name = "products",
    indexes = [
        Index(name = "idx_products_active_latest", columnList = "deleted_at,created_at DESC,id DESC"),
        Index(name = "idx_products_active_brand_latest", columnList = "deleted_at,brand_id,created_at DESC,id DESC"),
        Index(name = "idx_products_active_price", columnList = "deleted_at,price ASC,id DESC"),
        Index(name = "idx_products_active_brand_price", columnList = "deleted_at,brand_id,price ASC,id DESC"),
        Index(name = "idx_products_active_likes", columnList = "deleted_at,like_count DESC,id DESC"),
        Index(name = "idx_products_active_brand_likes", columnList = "deleted_at,brand_id,like_count DESC,id DESC"),
    ],
)
class ProductJpaEntity(
    @Column(name = "brand_id", nullable = false)
    val brandId: Long,
    @Column(nullable = false, length = 255)
    var name: String,
    @Column(nullable = false)
    var price: Long,
    @Column(nullable = false)
    var stock: Int,
) : BaseEntity() {
    @Column(name = "like_count", nullable = false, insertable = false, updatable = false)
    @ColumnDefault("0")
    var likeCount: Long = 0
        private set

    fun toEntity(): ProductEntity = ProductEntity(brandId, name, price, stock, EntityState(id, createdAt, updatedAt, deletedAt))
}
