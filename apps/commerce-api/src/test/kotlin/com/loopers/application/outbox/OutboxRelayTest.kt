package com.loopers.application.outbox

import com.loopers.domain.outbox.BrokerReceipt
import com.loopers.domain.outbox.OutboxBacklog
import com.loopers.domain.outbox.OutboxFact
import com.loopers.domain.outbox.OutboxMessage
import com.loopers.domain.outbox.OutboxSender
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class OutboxRelayTest {
    @Test
    fun `scheduler and manual entry share one executing loop`() {
        val transactions = mock<OutboxTransactions>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fact = OutboxFact("event", 1, "OrderConfirmed", 1, "orders", "1", "{}")
        whenever(transactions.due()).thenReturn(listOf(fact.eventId))
        whenever(transactions.begin(any())).thenReturn(OutboxMessage(fact, Instant.EPOCH))
        whenever(transactions.backlog()).thenReturn(OutboxBacklog(0, null))
        val relay = OutboxRelay(
            transactions,
            object : OutboxSender {
            override fun send(fact: OutboxFact): BrokerReceipt {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))

                return BrokerReceipt(fact.topic, 0, 0)
            }
        },
        )
        val executor = Executors.newSingleThreadExecutor()
        try {
            val first = executor.submit<Int> { relay.runOnce() }
            check(entered.await(5, TimeUnit.SECONDS))

            assertThat(relay.runOnce(fact.eventId)).isZero()
            release.countDown()
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(1)
        } finally {
            release.countDown()
            executor.shutdownNow()
            check(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
