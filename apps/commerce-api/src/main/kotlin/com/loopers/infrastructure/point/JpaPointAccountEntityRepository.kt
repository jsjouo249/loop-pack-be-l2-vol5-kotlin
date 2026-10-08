package com.loopers.infrastructure.point

import com.loopers.infrastructure.common.JpaWriteOperations
import jakarta.persistence.EntityManager
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
class JpaPointAccountEntityRepository(
    private val jpa: PointAccountJpaRepository,
    private val em: EntityManager,
    private val writes: JpaWriteOperations,
) : PointAccountEntityRepository {
    override fun findByUserId(userId: Long): PointAccountEntity? = jpa.findByIdOrNull(userId)?.toEntity()

    @Transactional
    override fun create(entity: PointAccountEntity): PointAccountEntity {
        val target = PointAccountJpaEntity(entity.userId, entity.balance)
        // 기존 계정의 잔액을 덮어쓰는 병합 대신 INSERT만 수행해 중복 생성은 무결성 오류로 드러낸다.
        em.persist(target)
        em.flush()
        return target.toEntity()
    }

    override fun findByUserIdForUpdate(userId: Long): PointAccountEntity? =
        writes.lock(PointAccountJpaEntity::class.java, userId)?.toEntity()

    override fun saveBalance(entity: PointAccountEntity): PointAccountEntity {
        writes.flush()
        check(
            writes.update(
            "update point_balances set balance = :balance where user_id = :id",
            "id" to entity.userId, "balance" to entity.balance,
        ) == 1,
        ) { "Missing point account: ${entity.userId}" }
        return writes.current(PointAccountJpaEntity::class.java, entity.userId).toEntity()
    }
}
