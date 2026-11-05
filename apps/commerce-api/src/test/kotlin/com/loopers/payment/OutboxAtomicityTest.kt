package com.loopers.payment

import com.loopers.ControlledPaymentProvider
import com.loopers.SuccessPaymentProvider
import com.loopers.application.order.OrderItemInput
import com.loopers.application.outbox.OrderConfirmedOutbox
import com.loopers.application.payment.PaymentApplicationService
import com.loopers.application.transaction.CommerceTransactionTestSupport
import com.loopers.confirmWithTestCard
import com.loopers.domain.order.event.OrderConfirmed
import com.loopers.domain.order.event.PurchasedItem
import com.loopers.domain.outbox.OrderConfirmedCodec
import com.loopers.domain.outbox.OutboxRepository
import com.loopers.domain.payment.ProviderStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.reset
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.transaction.IllegalTransactionStateException
import java.time.Instant

class OutboxAtomicityTest : CommerceTransactionTestSupport() {
    @Autowired private lateinit var controlled: ControlledPaymentProvider

    @Autowired private lateinit var payments: PaymentApplicationService

    @Autowired private lateinit var outbox: OrderConfirmedOutbox

    @MockitoSpyBean private lateinit var repository: OutboxRepository

    @MockitoSpyBean private lateinit var codec: OrderConfirmedCodec

    private lateinit var pg: PgHttpFixture

    @BeforeEach
    fun startPg() {
        pg = PgHttpFixture()
        pg.createMode = "SUCCESS"
        controlled.delegate = pg.adapter
    }

    @AfterEach
    fun stopPg() {
        reset(repository, codec)
        controlled.delegate = SuccessPaymentProvider()
        pg.close()
    }

    @ParameterizedTest
    @ValueSource(strings = ["before-insert", "after-insert", "serialization"])
    fun `outbox failure rolls back only confirmation and same PG success recovers`(stage: String) {
        val user = buyer(10_000)
        val product = product(100, 5)
        val order = orders.create(user, listOf(OrderItemInput(product, 2))).id
        if (stage == "serialization") {
            doAnswer { error("injected serialization failure") }.`when`(codec).encode(any())
        } else {
            tx {
                doAnswer {
                    if (stage == "after-insert") it.callRealMethod()
                    error("injected Outbox failure")
                }.`when`(repository).insert(any())
            }
        }

        assertThrows<RuntimeException> { orders.confirmWithTestCard(user, order) }

        tx {
            em.flush()
            em.clear()
        }
        assertThat(orders.getForUser(user, order).status.name).isEqualTo("PAYMENT_PENDING")
        assertThat(number("select count(*) from orders where id=?", order)).isEqualTo(1)
        assertThat(number("select count(*) from stock_reservations where order_id=? and status='RESERVED'", order)).isEqualTo(1)
        assertThat(number("select count(*) from payment_attempts where order_id=? and status='REQUESTED'", order)).isEqualTo(1)
        assertThat(number("select count(*) from order_outbox")).isZero()
        assertThat(stock(product)).isEqualTo(3)
        assertThat(balance(user)).isEqualTo(10_000)
        assertThat(pg.transactions.values.single().status).isEqualTo(ProviderStatus.SUCCESS)
        assertThat(pg.creates.get()).isEqualTo(1)
        reset(repository, codec)

        orders.reconcile(user, order)
        orders.reconcile(user, order)
        orders.confirmWithTestCard(user, order)
        val transaction = pg.transactions.values.single()
        payments.callback(transaction.orderId, transaction.transactionKey)

        tx {
            em.flush()
            em.clear()
        }
        val event = checkNotNull(repository.find(OrderConfirmed.stableEventId(order)))
        assertThat(codec.decode(event.fact.payload)).isEqualTo(
            OrderConfirmed(event.fact.eventId, order, orders.getForUser(user, order).confirmedAt!!.toInstant(), listOf(PurchasedItem(product, 2)), 1),
        )
        assertThat(event.status.name).isEqualTo("PENDING")
        assertThat(number("select count(*) from order_outbox where order_id=?", order)).isEqualTo(1)
        assertThat(number("select count(*) from stock_reservations where order_id=? and status='CONSUMED'", order)).isEqualTo(1)
        assertThat(number("select count(*) from payment_attempts where order_id=? and status='SUCCESS'", order)).isEqualTo(1)
        assertThat(stock(product)).isEqualTo(3)
        assertThat(pg.creates.get()).isEqualTo(1)
    }

    @ParameterizedTest
    @ValueSource(strings = ["PENDING", "LOST", "REJECT"])
    fun `unconfirmed PG observations never create an Outbox`(mode: String) {
        pg.createMode = mode
        val user = buyer(0)
        val product = product(100, 5)
        val order = orders.create(user, listOf(OrderItemInput(product, 2))).id

        val result = orders.confirmWithTestCard(user, order)

        assertThat(result.paymentAttempt!!.status.name).isEqualTo(
            when (mode) {
                "LOST" -> "UNKNOWN"
                "REJECT" -> "FAILED"
                else -> "PENDING"
            },
        )
        assertThat(number("select count(*) from order_outbox")).isZero()
        assertThat(pg.creates.get()).isEqualTo(1)
    }

    @ParameterizedTest
    @ValueSource(strings = ["callback", "reconcile"])
    fun `first recovered PG success records one Outbox across repeated result paths`(path: String) {
        pg.createMode = "PENDING"
        val user = buyer(0)
        val product = product(100, 5)
        val order = orders.create(user, listOf(OrderItemInput(product, 2))).id
        orders.confirmWithTestCard(user, order)
        pg.complete(ProviderStatus.SUCCESS)
        val transaction = pg.transactions.values.single()

        if (path == "callback") payments.callback(transaction.orderId, transaction.transactionKey) else orders.reconcile(user, order)
        payments.callback(transaction.orderId, transaction.transactionKey)
        orders.reconcile(user, order)

        assertThat(number("select count(*) from order_outbox where order_id=?", order)).isEqualTo(1)
        assertThat(number("select count(*) from stock_reservations where order_id=? and status='CONSUMED'", order)).isEqualTo(1)
        assertThat(pg.creates.get()).isEqualTo(1)
    }

    @Test
    fun `outbox recording requires the caller transaction`() {
        val event = OrderConfirmed(OrderConfirmed.stableEventId(1), 1, Instant.EPOCH, listOf(PurchasedItem(1, 1)), 1)

        assertThrows<IllegalTransactionStateException> { outbox.record(event) }

        assertThat(number("select count(*) from order_outbox")).isZero()
    }
}
