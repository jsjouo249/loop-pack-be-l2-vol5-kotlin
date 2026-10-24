package com.loopers.payment

import com.loopers.domain.payment.ObservationKind
import com.loopers.domain.payment.ProviderStatus
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@Timeout(15)
class PaymentProviderContractTest {
    @Test
    fun `only matching numeric and boolean rejection fields authorize no effect`(): Unit = PgHttpFixture().use { pg ->
        pg.createMode = "REJECT"
        val template = """{"code":"TEST_PG_REJECTED_BEFORE_CREATE","orderId":"LP-ORD-42","amount":7000,"transactionCreated":false}"""
        listOf(
            template.replace("7000", "7001"),
            template.replace("LP-ORD-42", "LP-ORD-43"),
            template.replace("false", "\"false\""),
            template.replace("TEST_PG_REJECTED_BEFORE_CREATE", "OTHER"),
            "{}",
        ).forEach {
            pg.rejectionResponse = it
            assertThat(pg.adapter.create("LP-ORD-42", 7000).kind).isEqualTo(ObservationKind.UNKNOWN)
        }
        assertThat(pg.transactions).isEmpty()
        assertThat(pg.creates.get()).isEqualTo(5)
    }

    @Test
    fun `configured clock drives failed half open probe and later recovery`() {
        val clock = MutablePgClock()
        PgHttpFixture(clock = clock).use { pg ->
            pg.getCode = 503
            repeat(2) { pg.adapter.lookup("LP-ORD-42") }
            assertThat(pg.circuit.state).isEqualTo(CircuitBreaker.State.OPEN)
            clock.advance(5)
            clock.advanceMillis(1)
            pg.adapter.lookup("LP-ORD-42")
            assertThat(pg.lookups.get()).isEqualTo(5)
            assertThat(pg.circuit.state).isEqualTo(CircuitBreaker.State.OPEN)
            clock.advance(5)
            clock.advanceMillis(1)
            pg.getCode = 200
            assertThat(pg.adapter.lookup("LP-ORD-42").kind).isEqualTo(ObservationKind.NOT_FOUND)
            assertThat(pg.circuit.state).isEqualTo(CircuitBreaker.State.CLOSED)
            assertThat(pg.lookups.get()).isEqualTo(6)
        }
    }

    @Test
    fun `real HTTP create headers JSON types and both lookups follow fixed contract`(): Unit = PgHttpFixture().use { pg ->
        val order = "LP-ORD-0000000042"
        val accepted = pg.adapter.create(order, 7000)
        assertThat(accepted.kind).isEqualTo(ObservationKind.PENDING)
        val request = pg.requests.single()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.uri).isEqualTo("/api/v1/payments")
        assertThat(request.account).isEqualTo("135135")
        val json = pg.mapper.readTree(request.body)
        assertThat(json["orderId"].isTextual).isTrue()
        assertThat(json["amount"].isIntegralNumber).isTrue()
        assertThat(json["cardType"].asText()).isEqualTo("TEST")
        assertThat(json["cardNo"].asText()).isEqualTo("0000-0000-0000-0000")
        assertThat(json["callbackUrl"].asText()).isEqualTo(pg.properties.callbackUrl)
        pg.complete(ProviderStatus.SUCCESS)
        assertThat(pg.adapter.lookup(order).kind).isEqualTo(ObservationKind.SUCCESS)
        assertThat(pg.requests.drop(1).map { it.uri }).containsExactly("/api/v1/payments?orderId=$order", "/api/v1/payments/TX-1")
        assertThat(pg.requests).allMatch { it.account == "135135" }
    }

    @Test
    fun `only exact before create rejection proves no effect while response loss creates once`(): Unit = PgHttpFixture().use { pg ->
        pg.createMode = "REJECT"
        assertThat(pg.adapter.create("LP-ORD-42", 7000).kind).isEqualTo(ObservationKind.REJECTED_NO_EFFECT)
        assertThat(pg.transactions).isEmpty()
        pg.createMode = "BAD_422"
        assertThat(pg.adapter.create("LP-ORD-43", 7000).kind).isEqualTo(ObservationKind.UNKNOWN)
        pg.createMode = "LOST"
        assertThat(pg.adapter.create("LP-ORD-44", 7000).kind).isEqualTo(ObservationKind.UNKNOWN)
        assertThat(pg.transactions).hasSize(1)
        assertThat(pg.creates.get()).isEqualTo(3)
        pg.complete(ProviderStatus.SUCCESS)
        assertThat(pg.adapter.lookup("LP-ORD-44").kind).isEqualTo(ObservationKind.SUCCESS)
        assertThat(pg.creates.get()).isEqualTo(3)
    }

    @Test
    fun `malformed mismatched and non boolean rejection bodies are not no effect evidence`(): Unit = PgHttpFixture().use { pg ->
        listOf("not-json", "{}", """{"orderId":42,"amount":"7000","status":"SUCCESS"}""").forEach {
            pg.createResponse = it
            assertThat(pg.adapter.create("LP-ORD-42", 7000).kind).isEqualTo(ObservationKind.UNKNOWN)
        }
    }

    @Test
    fun `lookup encodes order query and detects zero and multiple transactions`(): Unit = PgHttpFixture().use { pg ->
        assertThat(pg.adapter.lookup("LP + & /42").kind).isEqualTo(ObservationKind.NOT_FOUND)
        assertThat(pg.requests.single().uri).doesNotContain(" ").contains("%")
        pg.adapter.create("LP-ORD-42", 0)
        pg.duplicate()
        val result = pg.adapter.lookup("LP-ORD-42")
        assertThat(result.kind).isEqualTo(ObservationKind.CONFLICT)
        assertThat(result.quarantine).isTrue()
    }

    @Test
    fun `GET retries are bounded and OPEN suppresses wire calls`(): Unit = PgHttpFixture().use { pg ->
        pg.getCode = 503
        repeat(2) { assertThat(pg.adapter.lookup("LP-ORD-42").kind).isEqualTo(ObservationKind.UNAVAILABLE) }
        assertThat(pg.lookups.get()).isEqualTo(4)
        assertThat(pg.circuit.state).isEqualTo(CircuitBreaker.State.OPEN)
        assertThat(pg.adapter.lookup("LP-ORD-42").issue).isEqualTo("CIRCUIT_OPEN")
        assertThat(pg.lookups.get()).isEqualTo(4)
        assertThat(pg.creates.get()).isZero()
    }

    @Test
    fun `actual socket timeout ends waiting and does not retry create`(): Unit = PgHttpFixture().use { pg ->
        val latch = CountDownLatch(1)
        pg.afterCreate = {
            latch.await(3, TimeUnit.SECONDS)
            Unit
        }
        try {
            val started = System.nanoTime()
            assertThat(pg.adapter.create("LP-ORD-42", 7000).kind).isEqualTo(ObservationKind.UNKNOWN)
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isBetween(350L, 1800L)
            assertThat(pg.transactions).hasSize(1)
            assertThat(pg.creates.get()).isEqualTo(1)
        } finally {
            latch.countDown()
        }
    }
}

class MutablePgClock : java.time.Clock() {
    private var time = java.time.Instant.parse("2026-10-21T00:00:00Z")
    fun advanceMillis(millis: Long) {
        time = time.plusMillis(millis)
    }
    fun advance(seconds: Long) {
        time = time.plusSeconds(seconds)
    }
    override fun getZone(): java.time.ZoneId = java.time.ZoneOffset.UTC
    override fun withZone(zone: java.time.ZoneId): java.time.Clock = this
    override fun instant(): java.time.Instant = time
}
