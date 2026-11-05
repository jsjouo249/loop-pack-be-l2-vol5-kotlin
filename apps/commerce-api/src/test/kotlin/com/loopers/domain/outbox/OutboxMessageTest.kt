package com.loopers.domain.outbox

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant

class OutboxMessageTest {
    @Test
    fun `attempt persists bounded backoff and publication preserves original fact`() {
        val now = Instant.parse("2026-11-05T00:00:00Z")
        val fact = OutboxFact("event-1", 1, "OrderConfirmed", 1, "orders", "1", "{\"original\":true}")
        val message = OutboxMessage(fact, now)

        message.beginAttempt(now, Duration.ofSeconds(5), Duration.ofMinutes(5))

        assertThat(message.nextAttemptAt).isEqualTo(now.plusSeconds(5))
        assertThat(message.attemptCount).isEqualTo(1)
        assertThrows<IllegalStateException> { message.beginAttempt(now, Duration.ofSeconds(5), Duration.ofMinutes(5)) }

        message.failed("TimeoutException", now.plusSeconds(12), Duration.ofSeconds(5), Duration.ofMinutes(5))
        assertThat(message.nextAttemptAt).isEqualTo(now.plusSeconds(17))

        repeat(10) {
            val next = message.nextAttemptAt
            message.beginAttempt(next, Duration.ofSeconds(5), Duration.ofMinutes(5))
            assertThat(Duration.between(next, message.nextAttemptAt)).isLessThanOrEqualTo(Duration.ofMinutes(5))
        }

        message.published(message.nextAttemptAt)

        assertThat(message.status).isEqualTo(OutboxStatus.PUBLISHED)
        assertThat(message.fact).isEqualTo(fact)
        assertThat(message.lastError).isNull()
        assertThrows<IllegalStateException> { message.beginAttempt(message.nextAttemptAt, Duration.ofSeconds(5), Duration.ofMinutes(5)) }
    }
}
