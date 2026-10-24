package com.loopers.infrastructure.payment

import com.loopers.domain.payment.AttemptStatus
import com.loopers.domain.payment.PaymentAttempt
import com.loopers.domain.payment.PaymentObservation
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
import java.time.ZonedDateTime
import java.util.UUID

@Entity
@Table(
    name = "payment_attempts",
    uniqueConstraints = [
    UniqueConstraint(name = "uk_payment_attempt_key", columnNames = ["attempt_key"]),
    UniqueConstraint(name = "uk_payment_provider_order", columnNames = ["provider_account", "provider_order_id"]),
    UniqueConstraint(name = "uk_payment_transaction", columnNames = ["provider_account", "transaction_key"]),
],
)
@Check(constraints = "amount >= 0")
class PaymentAttemptJpaEntity(
    @Id @Column(name = "order_id") val orderId: Long,
    @Column(name = "user_id", nullable = false) val userId: Long,
    @Column(
        name = "attempt_key",
        nullable = false,
        columnDefinition = "varchar(128) character set ascii collate ascii_bin",
    ) val attemptKey: String,
    @Column(nullable = false) val amount: Long,
    @Column(name = "provider_order_id", nullable = false, length = 64) val providerOrderId: String,
    @Column(name = "provider_account", nullable = false, length = 20) val account: String,
    @Column(name = "requested_at", nullable = false) val requestedAt: ZonedDateTime,
) {
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status = AttemptStatus.REQUESTED

    @Column(
        name = "transaction_key",
        length = 128,
        columnDefinition = "varchar(128) character set utf8mb4 collate utf8mb4_0900_bin",
    )
    var transactionKey:
        String? = null

    @Column(nullable = false)
    var quarantined = false

    @Column(name = "last_check", length = 64)
    var lastCheck: String? = null
    fun domain() = PaymentAttempt(
        orderId, userId, attemptKey, amount, providerOrderId, account,
        status, transactionKey, quarantined, lastCheck, requestedAt,
    )
    fun update(attempt: PaymentAttempt) {
        status = attempt.status
        transactionKey = attempt.transactionKey
        quarantined = attempt.quarantined
        lastCheck = attempt.lastCheck
    }
}

@Entity
@Table(name = "payment_issues")
class PaymentIssueJpaEntity(
    @Column(name = "order_id", nullable = false) val orderId: Long,
    @Column(nullable = false, length = 64) val reason: String,
    @Column(name = "observed_transaction_key", length = 128) val transactionKey: String?,
    @Column(name = "observed_order_id", length = 128) val providerOrderId: String?,
    @Column(name = "observed_amount") val amount: Long?,
    @Column(name = "observed_status", length = 32) val status: String,
) {
    @Id
    @Column(length = 36)
    val id: String = UUID.randomUUID().toString()

    @Column(name = "created_at", nullable = false)
    val createdAt: ZonedDateTime = ZonedDateTime.now()
}

@Component
@Transactional(readOnly = true)
class JpaPaymentAttemptStorage(private val em: EntityManager) : PaymentAttemptStorage {
    override fun find(orderId: Long): PaymentAttempt? = em.find(PaymentAttemptJpaEntity::class.java, orderId)?.domain()
    override fun findForUpdate(orderId: Long): PaymentAttempt? =
        em.find(PaymentAttemptJpaEntity::class.java, orderId, LockModeType.PESSIMISTIC_WRITE)?.domain()
    override fun findByKey(key: String): PaymentAttempt? = query("a.attemptKey", key)
    override fun findByProviderOrderId(providerOrderId: String): PaymentAttempt? = query("a.providerOrderId", providerOrderId)
    private fun query(field: String, value: String): PaymentAttempt? = em.createQuery(
        "select a from PaymentAttemptJpaEntity a where $field = :value",
        PaymentAttemptJpaEntity::class.java,
    ).setParameter("value", value).resultList.firstOrNull()?.domain()
    override fun findAll(orderIds: Collection<Long>): List<PaymentAttempt> = if (orderIds.isEmpty()) {
        emptyList()
    } else {
        em.createQuery(
        "select a from PaymentAttemptJpaEntity a where a.orderId in :ids",
            PaymentAttemptJpaEntity::class.java,
    ).setParameter("ids", orderIds).resultList.map { it.domain() }
    }

    @Transactional
    override fun create(attempt: PaymentAttempt) {
        em.persist(
            PaymentAttemptJpaEntity(
                attempt.orderId,
                attempt.userId,
                attempt.key,
                attempt.amount,
                attempt.providerOrderId,
                attempt.account,
                attempt.requestedAt,
            ),
        )
        em.flush()
    }

    @Transactional
    override fun save(attempt: PaymentAttempt) {
        checkNotNull(em.find(PaymentAttemptJpaEntity::class.java, attempt.orderId)).update(attempt)
        em.flush()
    }

    @Transactional
    override fun recordIssue(attempt: PaymentAttempt, observation: PaymentObservation, reason: String) {
        val t = observation.transaction
        em.persist(
            PaymentIssueJpaEntity(
                attempt.orderId,
                reason,
                t?.transactionKey?.take(128),
                t?.orderId?.take(128),
                t?.amount,
                observation.kind.name,
            ),
        )
    }
}
