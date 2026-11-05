package com.loopers.application.outbox

import com.loopers.domain.outbox.OutboxMessage
import com.loopers.domain.outbox.OutboxRepository
import com.loopers.domain.outbox.OutboxSender
import com.loopers.domain.outbox.OutboxStatus
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

@ConfigurationProperties("outbox.relay")
data class OutboxRelaySettings(
    val batchSize: Int = 20,
    val initialBackoff: Duration = Duration.ofSeconds(5),
    val maximumBackoff: Duration = Duration.ofMinutes(5),
) {
    init {
        require(batchSize in 1..100 && !initialBackoff.isNegative && !initialBackoff.isZero)
        require(maximumBackoff >= initialBackoff)
    }
}

@Service
class OutboxTransactions(
    private val repository: OutboxRepository,
    private val clock: Clock,
    private val settings: OutboxRelaySettings,
) {
    @Transactional(readOnly = true)
    fun due(): List<String> = repository.due(clock.instant(), settings.batchSize)

    @Transactional
    fun begin(eventId: String): OutboxMessage? {
        val message = repository.find(eventId) ?: error("Unknown Outbox eventId=$eventId")
        if (message.status != OutboxStatus.PENDING || message.nextAttemptAt.isAfter(clock.instant())) return null

        message.beginAttempt(clock.instant(), settings.initialBackoff, settings.maximumBackoff)
        repository.saveDelivery(message)

        return message
    }

    @Transactional
    fun published(message: OutboxMessage) {
        message.published(clock.instant())
        repository.saveDelivery(message)
    }

    @Transactional
    fun failed(eventId: String, failure: Exception) {
        val message = checkNotNull(repository.find(eventId))
        check(message.status == OutboxStatus.PENDING)
        message.failed(failureType(failure), clock.instant(), settings.initialBackoff, settings.maximumBackoff)
        repository.saveDelivery(message)
    }

    @Transactional(readOnly = true)
    fun backlog() = repository.backlog()
}

@Service
class OutboxRelay(private val transactions: OutboxTransactions, private val sender: OutboxSender) {
    private val running = AtomicBoolean()
    private val log = LoggerFactory.getLogger(javaClass)

    fun runOnce(eventId: String? = null): Int {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) { "Relay must run outside a DB transaction" }
        if (!running.compareAndSet(false, true)) return 0

        try {
            var published = 0
            for (id in eventId?.let(::listOf) ?: transactions.due()) {
                val message = transactions.begin(id) ?: continue
                try {
                    val receipt = sender.send(message.fact)
                    log.info(
                        "outbox broker_ack eventId={} orderId={} topic={} partition={} offset={}",
                        id,
                        message.fact.orderId,
                        receipt.topic,
                        receipt.partition,
                        receipt.offset,
                    )
                    transactions.published(message)
                    published++
                    log.info(
                        "outbox published eventId={} orderId={} topic={} partition={} offset={} attempt={}",
                        id,
                        message.fact.orderId,
                        receipt.topic,
                        receipt.partition,
                        receipt.offset,
                        message.attemptCount,
                    )
                } catch (failure: Exception) {
                    transactions.failed(id, failure)
                    log.warn(
                        "outbox unresolved eventId={} orderId={} errorType={}",
                        id,
                        message.fact.orderId,
                        failureType(failure),
                    )
                    if (failure is InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
            val backlog = transactions.backlog()
            log.info("outbox backlog pending={} oldestCreatedAt={}", backlog.pendingCount, backlog.oldestCreatedAt)

            return published
        } finally {
            running.set(false)
        }
    }
}

private fun failureType(failure: Throwable): String =
    generateSequence(failure) { it.cause }.take(16).last().javaClass.simpleName.take(128)
