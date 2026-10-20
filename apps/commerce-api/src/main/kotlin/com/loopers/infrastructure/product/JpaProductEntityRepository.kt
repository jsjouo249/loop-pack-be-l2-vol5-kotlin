package com.loopers.infrastructure.product

import com.loopers.domain.product.ProductSort
import com.loopers.infrastructure.common.JpaWriteOperations
import org.springframework.transaction.annotation.Transactional
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Repository

@Repository
class JpaProductEntityRepository(
    private val jpa: ProductJpaRepository,
    private val writes: JpaWriteOperations,
) : ProductEntityRepository {
    @Transactional
    override fun create(entity: ProductEntity): ProductEntity {
        require(entity.state.id == 0L)
        return jpa.saveAndFlush(ProductJpaEntity(entity.brandId, entity.name, entity.price, entity.stock)).toEntity()
    }

    override fun findActiveForUpdate(id: Long): ProductEntity? =
        writes.lock(ProductJpaEntity::class.java, id)?.takeIf { it.deletedAt == null }?.toEntity()

    override fun findAnyForUpdate(id: Long): ProductEntity? = writes.lock(ProductJpaEntity::class.java, id)?.toEntity()

    override fun findActiveIdsByBrandId(brandId: Long): List<Long> = jpa.findActiveIdsByBrandId(brandId)

    override fun updateDetails(change: ProductDetailsChange): ProductEntity? {
        writes.flush()
        // MySQL의 대입 순서를 고려해 새 값을 넣기 전에 변경 여부를 판정하고, 이름의 대소문자도 구분한다.
        val matched = writes.update(
            "update products set updated_at = if(binary name <> binary :name or price <> :price, " +
                "current_timestamp(6), updated_at), name = :name, price = :price where id = :id and deleted_at is null",
            "id" to change.id,
            "name" to change.name,
            "price" to change.price,
        )
        // useAffectedRows=false이므로 동일 값 설정도 일치 행 1건이다. 실제 변경이 없으면 수정 시각은 유지한다.
        return if (matched == 0) null else writes.current(ProductJpaEntity::class.java, change.id).toEntity()
    }

    override fun setStock(change: ProductStockChange): ProductEntity? {
        writes.flush()
        return if (updateStock(change) == 0) null else writes.current(ProductJpaEntity::class.java, change.id).toEntity()
    }

    override fun saveStockChanges(changes: Collection<ProductStockChange>) {
        if (changes.isEmpty()) return
        // 주문에서 이미 잠근 상품의 재고만 저장한다. 이름·가격·삭제 상태는 덮어쓰지 않는다.
        writes.flush()
        changes.sortedBy { it.id }.forEach { change ->
            check(updateStock(change) == 1) { "Locked product disappeared: ${change.id}" }
            writes.current(ProductJpaEntity::class.java, change.id)
        }
    }

    private fun updateStock(change: ProductStockChange): Int = writes.update(
        "update products set updated_at = if(stock <> :stock, current_timestamp(6), updated_at), " +
            "stock = :stock where id = :id and deleted_at is null",
        "id" to change.id,
        "stock" to change.stock,
    )

    override fun markDeleted(change: ProductDeletion): Boolean {
        writes.flush()
        val matched = updateDeletion(change)
        if (matched != 0) writes.current(ProductJpaEntity::class.java, change.id)
        return matched == 1
    }

    override fun markDeletedAll(changes: Collection<ProductDeletion>) {
        if (changes.isEmpty()) return
        writes.flush()
        changes.sortedBy { it.id }.forEach { change ->
            check(updateDeletion(change) == 1) { "Locked product disappeared: ${change.id}" }
            writes.current(ProductJpaEntity::class.java, change.id)
        }
    }

    private fun updateDeletion(change: ProductDeletion): Int = writes.update(
            "update products set deleted_at = :deletedAt, updated_at = current_timestamp(6) " +
                "where id = :id and deleted_at is null",
            "id" to change.id,
            "deletedAt" to change.deletedAt,
        )

    override fun findActive(id: Long): ProductEntity? = jpa.findByIdAndDeletedAtIsNull(id)?.toEntity()

    override fun findAny(id: Long): ProductEntity? = jpa.findByIdOrNull(id)?.toEntity()

    override fun findActiveByIds(ids: Collection<Long>): List<ProductEntity> =
        jpa.findByIdInAndDeletedAtIsNull(ids).map(ProductJpaEntity::toEntity)

    override fun existsActiveByBrandId(brandId: Long): Boolean = jpa.existsByBrandIdAndDeletedAtIsNull(brandId)

    override fun findActivePage(brandId: Long?, offset: Int, limit: Int, sort: ProductSort): List<ProductEntity> {
        val ids = jpa.activePageIds(brandId, offset, limit, sort)
        if (ids.isEmpty()) return emptyList()
        val found = jpa.findByIdInAndDeletedAtIsNull(ids).associateBy { it.id }
        return ids.map { checkNotNull(found[it]).toEntity() }
    }

    override fun countActive(brandId: Long?): Long =
        if (brandId == null) jpa.countByDeletedAtIsNull() else jpa.countByBrandIdAndDeletedAtIsNull(brandId)
}
