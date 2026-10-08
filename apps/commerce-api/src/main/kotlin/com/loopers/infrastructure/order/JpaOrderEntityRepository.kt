package com.loopers.infrastructure.order

import com.loopers.infrastructure.common.JpaWriteOperations
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
class JpaOrderEntityRepository(
    private val jpa: OrderJpaRepository,
    private val writes: JpaWriteOperations,
) : OrderEntityRepository {
    @Transactional
    override fun create(entity: OrderEntity): OrderEntity {
        require(entity.state.id == 0L)
        val target = OrderJpaEntity(entity.userId, entity.totalAmount).also { order ->
            entity.items.forEach(order::addLine)
        }
        return jpa.saveAndFlush(target).toEntity()
    }

    override fun findForUpdate(id: Long): OrderEntity? = writes.lock(OrderJpaEntity::class.java, id)?.toEntity()

    override fun saveConfirmation(change: OrderConfirmation): OrderEntity {
        // 잠근 DRAFT의 확정 정보만 변경해 주문 품목과 주문 당시 가격 스냅샷을 보존한다.
        writes.flush()
        check(
            writes.update(
            "update orders set status = :status, payment_amount = :amount, payment_result = :result, " +
                "confirmed_at = :confirmedAt, updated_at = current_timestamp(6) where id = :id and status = 'DRAFT'",
            "id" to change.id, "status" to change.status.name, "amount" to change.paymentAmount,
            "result" to change.paymentResult.name, "confirmedAt" to change.confirmedAt,
        ) == 1,
        )
        return writes.current(OrderJpaEntity::class.java, change.id).toEntity()
    }

    @Transactional(readOnly = true)
    override fun find(id: Long): OrderEntity? = jpa.findByIdOrNull(id)?.toEntity()

    @Transactional(readOnly = true)
    override fun findPage(userId: Long?, offset: Int, limit: Int): List<OrderEntity> {
        val pageable = PageRequest.of(offset / limit, limit, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")))
        val page = if (userId == null) jpa.findAllBy(pageable) else jpa.findByUserId(userId, pageable)
        return page.map(OrderJpaEntity::toEntity)
    }

    override fun count(userId: Long?): Long = if (userId == null) jpa.count() else jpa.countByUserId(userId)
}
