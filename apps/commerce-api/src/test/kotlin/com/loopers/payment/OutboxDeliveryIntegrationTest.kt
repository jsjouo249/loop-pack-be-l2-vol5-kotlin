package com.loopers.payment

import com.loopers.application.order.OrderItemInput
import com.loopers.application.outbox.OutboxRecovery
import com.loopers.application.outbox.OutboxRelay
import com.loopers.application.outbox.OutboxTransactions
import com.loopers.application.transaction.CommerceTransactionTestSupport
import com.loopers.confirmWithTestCard
import com.loopers.domain.order.event.OrderConfirmed
import com.loopers.domain.outbox.OutboxRepository
import com.loopers.domain.outbox.OutboxSender
import com.loopers.domain.outbox.OutboxStatus
import com.loopers.domain.outbox.BrokerReceipt
import com.loopers.domain.outbox.OutboxFact
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.reset
import org.mockito.kotlin.whenever
import org.mockito.Mockito
import org.springframework.boot.autoconfigure.kafka.KafkaProperties
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.TopicPartition
import com.loopers.infrastructure.outbox.KafkaOutboxSender
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.time.Clock
import java.time.Instant
import java.util.concurrent.TimeoutException
import java.util.concurrent.CompletableFuture

class OutboxDeliveryIntegrationTest : CommerceTransactionTestSupport() {
    @Autowired private lateinit var relay: OutboxRelay

    @Autowired private lateinit var recovery: OutboxRecovery

    @Autowired private lateinit var transactions: OutboxTransactions

    @MockitoSpyBean private lateinit var repository: OutboxRepository

    @MockitoSpyBean private lateinit var clock: Clock

    @MockitoBean private lateinit var sender: OutboxSender

    private val now = Instant.parse("2026-11-05T00:00:00Z")

    @BeforeEach
    fun setTime() {
        whenever(clock.instant()).thenReturn(now)
    }

    @AfterEach
    fun resetFailures() {
        reset(repository, clock)
    }

    private fun confirmed(): Long {
        val user = buyer(0)
        val product = product(100, 5)

        return orders.create(user, listOf(OrderItemInput(product, 2))).id.also { orders.confirmWithTestCard(user, it) }
    }

    @Test
    fun `timeout keeps same payload with persisted backoff and later healthy entries proceed`() {
        val first = confirmed()
        val second = confirmed()
        val firstId = OrderConfirmed.stableEventId(first)
        val secondId = OrderConfirmed.stableEventId(second)
        val original = repository.find(firstId)!!.fact
        whenever(sender.send(any())).thenAnswer {
            val fact = it.getArgument<OutboxFact>(0)
            if (fact.eventId == firstId) throw TimeoutException("sensitive body must never be recorded")

            BrokerReceipt(fact.topic, 0, 0)
        }

        assertThat(relay.runOnce()).isEqualTo(1)

        val failed = repository.find(firstId)!!
        assertThat(failed.status).isEqualTo(OutboxStatus.PENDING)
        assertThat(failed.attemptCount).isEqualTo(1)
        assertThat(failed.nextAttemptAt).isEqualTo(now.plusSeconds(5))
        assertThat(failed.lastError).isEqualTo("TimeoutException")
        assertThat(repository.find(secondId)!!.status).isEqualTo(OutboxStatus.PUBLISHED)
        assertThat(relay.runOnce()).isZero()
        whenever(clock.instant()).thenReturn(now.plusSeconds(5))
        whenever(sender.send(any())).thenReturn(BrokerReceipt(original.topic, 0, 1))

        assertThat(relay.runOnce(firstId)).isEqualTo(1)
        assertThat(repository.find(firstId)!!.fact).isEqualTo(original)
        assertThat(repository.find(firstId)!!.attemptCount).isEqualTo(2)
    }

    @Test
    fun `failure after PUBLISHED flush rolls back state and leaves same event retryable`() {
        val order = confirmed()
        val id = OrderConfirmed.stableEventId(order)
        val original = repository.find(id)!!.fact
        whenever(sender.send(any())).thenReturn(BrokerReceipt(original.topic, 0, 0))
        tx {
            doAnswer {
                it.callRealMethod()
                if (it.getArgument<com.loopers.domain.outbox.OutboxMessage>(0).status == OutboxStatus.PUBLISHED) {
                    error("injected failure after PUBLISHED flush")
                }
                Unit
            }.`when`(repository).saveDelivery(any())
        }

        assertThat(relay.runOnce(id)).isZero()

        tx {
            em.flush()
            em.clear()
        }
        val pending = repository.find(id)!!
        assertThat(pending.status).isEqualTo(OutboxStatus.PENDING)
        assertThat(pending.publishedAt).isNull()
        assertThat(pending.lastError).isNotNull()
        assertThat(pending.fact).isEqualTo(original)
        reset(repository)
        whenever(clock.instant()).thenReturn(now.plusSeconds(5))

        assertThat(relay.runOnce(id)).isEqualTo(1)
        assertThat(repository.find(id)!!.status).isEqualTo(OutboxStatus.PUBLISHED)
    }

    @Test
    fun `late producer future success after local timeout cannot overwrite retry state`() {
        val order = confirmed()
        val id = OrderConfirmed.stableEventId(order)
        val fact = repository.find(id)!!.fact
        val future = CompletableFuture<SendResult<String, String>>()
        Mockito.mockConstruction(KafkaTemplate::class.java) { template, _ ->
            @Suppress("UNCHECKED_CAST")
            val typed = template as KafkaTemplate<String, String>
            whenever(typed.send(any<String>(), any<String>(), any<String>())).thenReturn(future)
        }.use {
            val actualSender = KafkaOutboxSender(KafkaProperties(), 1)
            try {
                assertThat(OutboxRelay(transactions, actualSender).runOnce(id)).isZero()
                val before = repository.find(id)!!
                assertThat(before.lastError).isEqualTo("TimeoutException")
                assertThat(before.status).isEqualTo(OutboxStatus.PENDING)

                future.complete(
                    SendResult(
                        ProducerRecord(fact.topic, fact.messageKey, fact.payload),
                        RecordMetadata(TopicPartition(fact.topic, 0), 4, 0, 0, 1, fact.payload.length),
                    ),
                )

                val after = repository.find(id)!!
                assertThat(after.status).isEqualTo(OutboxStatus.PENDING)
                assertThat(after.attemptCount).isEqualTo(before.attemptCount)
                assertThat(after.nextAttemptAt).isEqualTo(before.nextAttemptAt)
                assertThat(after.lastError).isEqualTo(before.lastError)
                assertThat(after.publishedAt).isNull()
            } finally {
                actualSender.close()
            }
        }
    }

    @Test
    fun `recovery refuses incomplete facts and conflicting existing payload without changing confirmation`() {
        val order = confirmed()
        val id = OrderConfirmed.stableEventId(order)
        assertThat(recovery.restore(order)).isEqualTo(id)
        val original = repository.find(id)!!.fact
        jdbc.update("update order_outbox set payload=? where event_id=?", original.payload.replace("\"quantity\":2", "\"quantity\":3"), id)

        assertThrows<IllegalStateException> { recovery.restore(order) }

        assertThat(number("select count(*) from order_outbox where order_id=?", order)).isEqualTo(1)
        assertThat(number("select count(*) from stock_reservations where order_id=? and status='CONSUMED'", order)).isEqualTo(1)
        val user = buyer(0)
        val product = product(100, 5)
        val draft = orders.create(user, listOf(OrderItemInput(product, 1))).id

        assertThrows<IllegalStateException> { recovery.restore(draft) }
        assertThat(number("select count(*) from order_outbox where order_id=?", draft)).isZero()
    }
}
