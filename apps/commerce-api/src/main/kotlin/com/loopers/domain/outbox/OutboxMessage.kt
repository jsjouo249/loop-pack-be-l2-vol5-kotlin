package com.loopers.domain.outbox

import java.time.Duration
import java.time.Instant

enum class OutboxStatus { PENDING, PUBLISHED }

data class OutboxFact(
    val eventId: String,
    val orderId: Long,
    val eventType: String,
    val schemaVersion: Int,
    val topic: String,
    val messageKey: String,
    val payload: String,
) {
    init {
        require(eventId.isNotBlank() && orderId > 0 && schemaVersion > 0)
        require(eventType.isNotBlank() && topic.isNotBlank() && messageKey == orderId.toString() && payload.isNotBlank())
    }
}

class OutboxMessage(
    val fact: OutboxFact,
    val createdAt: Instant,
    status: OutboxStatus = OutboxStatus.PENDING,
    attemptCount: Int = 0,
    nextAttemptAt: Instant = createdAt,
    lastError: String? = null,
    publishedAt: Instant? = null,
) {
    init {
        require(attemptCount >= 0 && (status == OutboxStatus.PUBLISHED) == (publishedAt != null))
    }

    var status = status
        private set

    var attemptCount = attemptCount
        private set

    var nextAttemptAt = nextAttemptAt
        private set

    var lastError = lastError
        private set

    var publishedAt = publishedAt
        private set

    fun beginAttempt(now: Instant, initialDelay: Duration, maximumDelay: Duration) {
        check(status == OutboxStatus.PENDING && !nextAttemptAt.isAfter(now))
        require(!initialDelay.isNegative && !initialDelay.isZero && maximumDelay >= initialDelay)
        attemptCount = Math.incrementExact(attemptCount)
        nextAttemptAt = now.plus(retryDelay(initialDelay, maximumDelay))
        lastError = "AWAITING_BROKER_CONFIRMATION"
    }

    fun published(now: Instant) {
        check(status == OutboxStatus.PENDING && attemptCount > 0)
        status = OutboxStatus.PUBLISHED
        publishedAt = now
        lastError = null
    }

    fun failed(errorType: String, now: Instant, initialDelay: Duration, maximumDelay: Duration) {
        check(status == OutboxStatus.PENDING && attemptCount > 0)
        lastError = errorType.take(128)
        nextAttemptAt = maxOf(nextAttemptAt, now.plus(retryDelay(initialDelay, maximumDelay)))
    }

    private fun retryDelay(initialDelay: Duration, maximumDelay: Duration): Duration =
        initialDelay.multipliedBy(1L shl minOf(attemptCount - 1, 30)).coerceAtMost(maximumDelay)
}

data class OutboxBacklog(val pendingCount: Long, val oldestCreatedAt: Instant?)

interface OutboxRepository {
    fun insert(message: OutboxMessage)
    fun find(eventId: String): OutboxMessage?
    fun due(now: Instant, limit: Int): List<String>
    fun saveDelivery(message: OutboxMessage)
    fun backlog(): OutboxBacklog
}

data class BrokerReceipt(val topic: String, val partition: Int, val offset: Long)

interface OutboxSender {
    fun send(fact: OutboxFact): BrokerReceipt
}
