package com.loopers.infrastructure.inventory

import com.loopers.domain.inventory.ReservationStatus
import com.loopers.domain.inventory.StockReservation
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityManager
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.Check
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Entity
@Table(name = "stock_reservations", uniqueConstraints = [UniqueConstraint(columnNames = ["order_id", "product_id"])])
@Check(constraints = "quantity > 0")
class StockReservationJpaEntity(
    @Id @Column(length = 64) val id: String,
    @Column(name = "order_id", nullable = false) val orderId: Long,
    @Column(name = "product_id", nullable = false) val productId: Long,
    @Column(nullable = false) val quantity: Int,
) {
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status = ReservationStatus.RESERVED
    fun domain() = StockReservation(orderId, productId, quantity, status)
}

@Component
@Transactional(readOnly = true)
class JpaStockReservationStorage(private val em: EntityManager) : StockReservationStorage {
    @Transactional
    override fun create(reservations: List<StockReservation>) {
        reservations.forEach { em.persist(StockReservationJpaEntity(id(it), it.orderId, it.productId, it.quantity)) }
        em.flush()
    }
    override fun findForUpdate(orderId: Long): List<StockReservation> {
        val ids = em.createQuery(
            "select r.id from StockReservationJpaEntity r where r.orderId = :id order by r.productId",
            String::class.java,
        )
            .setParameter("id", orderId).resultList
        return ids.map { em.find(StockReservationJpaEntity::class.java, it, LockModeType.PESSIMISTIC_WRITE).domain() }
    }

    @Transactional
    override fun save(reservations: List<StockReservation>) {
        reservations.forEach { checkNotNull(em.find(StockReservationJpaEntity::class.java, id(it))).status = it.status }
        em.flush()
    }
    private fun id(r: StockReservation) = "${r.orderId}:${r.productId}"
}
