package com.loopers.infrastructure.outbox

import com.loopers.domain.outbox.OutboxBacklog
import com.loopers.domain.outbox.OutboxFact
import com.loopers.domain.outbox.OutboxMessage
import com.loopers.domain.outbox.OutboxRepository
import com.loopers.domain.outbox.OutboxStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityManager
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.Check
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Entity
@Table(
    name = "order_outbox",
    uniqueConstraints = [UniqueConstraint(name = "uk_order_outbox_fact", columnNames = ["order_id", "event_type"])],
    indexes = [Index(name = "ix_order_outbox_due", columnList = "status,next_attempt_at,created_at,event_id")],
)
@Check(
    constraints = "order_id > 0 and schema_version > 0 and attempt_count >= 0 and " +
    "((status = 'PENDING' and published_at is null) or (status = 'PUBLISHED' and published_at is not null))",
)
class OutboxJpaEntity(message: OutboxMessage) {
    @Id
    @Column(name = "event_id", length = 36)
    val eventId = message.fact.eventId

    @Column(name = "order_id", nullable = false, updatable = false)
    val orderId = message.fact.orderId

    @Column(name = "event_type", nullable = false, updatable = false, length = 64)
    val eventType = message.fact.eventType

    @Column(name = "schema_version", nullable = false, updatable = false)
    val schemaVersion = message.fact.schemaVersion

    @Column(nullable = false, updatable = false)
    val topic = message.fact.topic

    @Column(name = "message_key", nullable = false, updatable = false, length = 64)
    val messageKey = message.fact.messageKey

    @Column(nullable = false, updatable = false, columnDefinition = "longtext")
    val payload = message.fact.payload

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt = message.createdAt

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status = message.status

    @Column(name = "attempt_count", nullable = false)
    var attemptCount = message.attemptCount

    @Column(name = "next_attempt_at", nullable = false)
    var nextAttemptAt = message.nextAttemptAt

    @Column(name = "last_error", length = 128)
    var lastError = message.lastError

    @Column(name = "published_at")
    var publishedAt = message.publishedAt

    fun domain() = OutboxMessage(
        OutboxFact(eventId, orderId, eventType, schemaVersion, topic, messageKey, payload),
        createdAt,
        status,
        attemptCount,
        nextAttemptAt,
        lastError,
        publishedAt,
    )
}

@Repository
@Transactional(readOnly = true)
class JpaOutboxRepository(private val em: EntityManager) : OutboxRepository {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun insert(message: OutboxMessage) {
        em.persist(OutboxJpaEntity(message))
        em.flush()
    }

    override fun find(eventId: String): OutboxMessage? = em.find(OutboxJpaEntity::class.java, eventId)?.domain()

    override fun due(now: Instant, limit: Int): List<String> = em.createQuery(
        "select o.eventId from OutboxJpaEntity o where o.status = :status and o.nextAttemptAt <= :now " +
            "order by o.nextAttemptAt, o.createdAt, o.eventId",
        String::class.java,
    ).setParameter("status", OutboxStatus.PENDING).setParameter("now", now).setMaxResults(limit).resultList

    @Transactional(propagation = Propagation.MANDATORY)
    override fun saveDelivery(message: OutboxMessage) {
        val target = checkNotNull(em.find(OutboxJpaEntity::class.java, message.fact.eventId))
        check(target.status == OutboxStatus.PENDING)
        target.status = message.status
        target.attemptCount = message.attemptCount
        target.nextAttemptAt = message.nextAttemptAt
        target.lastError = message.lastError
        target.publishedAt = message.publishedAt
        em.flush()
    }

    override fun backlog(): OutboxBacklog {
        val row = em.createQuery(
            "select count(o), min(o.createdAt) from OutboxJpaEntity o where o.status = :status",
            Array<Any>::class.java,
        ).setParameter("status", OutboxStatus.PENDING).singleResult

        return OutboxBacklog(row[0] as Long, row[1] as Instant?)
    }
}
