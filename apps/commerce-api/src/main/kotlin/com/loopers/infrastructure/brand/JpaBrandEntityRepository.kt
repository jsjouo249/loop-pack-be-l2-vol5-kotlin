package com.loopers.infrastructure.brand

import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import com.loopers.infrastructure.common.JpaWriteOperations
import org.springframework.transaction.annotation.Transactional
import org.springframework.stereotype.Repository

@Repository
class JpaBrandEntityRepository(
    private val jpa: BrandJpaRepository,
    private val writes: JpaWriteOperations,
) : BrandEntityRepository {
    @Transactional
    override fun create(entity: BrandEntity): BrandEntity {
        require(entity.state.id == 0L)
        return jpa.saveAndFlush(BrandJpaEntity(entity.name)).toEntity()
    }

    override fun findActiveForUpdate(id: Long): BrandEntity? =
        writes.lock(BrandJpaEntity::class.java, id)?.takeIf { it.deletedAt == null }?.toEntity()

    override fun updateName(change: BrandNameChange): BrandEntity? {
        writes.flush()
        // 이름 대입 전에 대소문자를 구분해 비교하며, 동일 이름 요청은 성공하되 수정 시각은 유지한다.
        val matched = writes.update(
            "update brands set updated_at = if(binary name <> binary :name, current_timestamp(6), updated_at), " +
                "name = :name where id = :id and deleted_at is null",
            "id" to change.id,
            "name" to change.name,
        )
        return if (matched == 0) null else writes.current(BrandJpaEntity::class.java, change.id).toEntity()
    }

    override fun markDeleted(change: BrandDeletion) {
        writes.flush()
        check(
            writes.update(
            "update brands set deleted_at = :deletedAt, updated_at = current_timestamp(6) " +
                "where id = :id and deleted_at is null",
            "id" to change.id, "deletedAt" to change.deletedAt,
        ) == 1,
        )
        writes.current(BrandJpaEntity::class.java, change.id)
    }

    override fun findActive(id: Long): BrandEntity? = jpa.findByIdAndDeletedAtIsNull(id)?.toEntity()

    override fun findActiveByIds(ids: Collection<Long>): List<BrandEntity> =
        if (ids.isEmpty()) emptyList() else jpa.findByIdInAndDeletedAtIsNull(ids).map(BrandJpaEntity::toEntity)

    override fun findActivePage(offset: Int, limit: Int): List<BrandEntity> =
        jpa.findByDeletedAtIsNull(
            PageRequest.of(offset / limit, limit, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))),
        ).map(BrandJpaEntity::toEntity)

    override fun countActive(): Long = jpa.countByDeletedAtIsNull()
}
