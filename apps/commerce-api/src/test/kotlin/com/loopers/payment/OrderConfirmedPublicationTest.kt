package com.loopers.payment

import com.loopers.ControlledPaymentProvider
import com.loopers.SuccessPaymentProvider
import com.loopers.application.order.OrderItemInput
import com.loopers.application.payment.PaymentApplicationService
import com.loopers.application.transaction.CommerceTransactionTestSupport
import com.loopers.confirmWithTestCard
import com.loopers.domain.order.event.OrderConfirmed
import com.loopers.domain.order.event.OrderConfirmedPublisher
import com.loopers.domain.order.event.PurchasedItem
import com.loopers.domain.payment.ObservationKind
import com.loopers.domain.payment.PaymentObservation
import com.loopers.domain.payment.PaymentProvider
import com.loopers.domain.payment.PaymentAttemptRepository
import com.loopers.domain.payment.ProviderStatus
import com.loopers.domain.payment.ProviderTransaction
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.reset
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.InvalidDataAccessApiUsageException
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.time.Instant
import java.util.Calendar
import java.util.TimeZone

class OrderConfirmedPublicationTest : CommerceTransactionTestSupport() {
    @Autowired private lateinit var publisher: OrderConfirmedPublisher

    @Autowired private lateinit var events: ApplicationEventPublisher

    @Autowired private lateinit var controlled: ControlledPaymentProvider

    @Autowired private lateinit var payments: PaymentApplicationService

    @MockitoSpyBean private lateinit var paymentAttempts: PaymentAttemptRepository

    @BeforeEach fun resetPublisher() = reset(publisher)

    @AfterEach fun resetProvider() {
        reset(publisher)
        controlled.delegate = SuccessPaymentProvider()
    }

    @Test
    fun `first PG success publishes committed snapshot once across confirm callback and reconcile`() {
        val user = buyer(0)
        val product = product(100, 5)
        val order = orders.create(user, listOf(OrderItemInput(product, 2))).id
        val sent = mutableListOf<OrderConfirmed>()

        doAnswer {
            val event = it.getArgument<OrderConfirmed>(0)
            // A separate connection sees only committed state, not the publisher's persistence context.
            dataSource.connection.use { connection ->
                connection.prepareStatement("select status, confirmed_at from orders where id=?").use { statement ->
                    statement.setLong(1, order)
                    statement.executeQuery().use { result ->
                        check(result.next())
                        assertThat(result.getString(1)).isEqualTo("CONFIRMED")
                        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                        assertThat(result.getTimestamp(2, utc).toInstant()).isEqualTo(event.confirmedAt)
                    }
                }
            }
            sent.add(event)
            Unit
        }.whenever(publisher).publish(any())

        orders.confirmWithTestCard(user, order)

        controlled.delegate = object : PaymentProvider {
            override fun create(providerOrderId: String, amount: Long): PaymentObservation = error("must not create again")
            override fun lookup(providerOrderId: String, expectedTransactionKey: String?, callback: Boolean) = PaymentObservation(
                ObservationKind.SUCCESS, ProviderTransaction("TX-$providerOrderId", providerOrderId, 200, ProviderStatus.SUCCESS),
            )
        }
        orders.confirmWithTestCard(user, order)
        orders.reconcile(user, order)
        val providerOrder = "LP-ORD-${order.toString().padStart(10, '0')}"
        payments.callback(providerOrder, "TX-$providerOrder")

        assertThat(sent).hasSize(1)
        assertThat(sent.single().eventId).isEqualTo(OrderConfirmed.stableEventId(order))
        assertThat(sent.single().items).containsExactly(PurchasedItem(product, 2))
        assertThat(stock(product)).isEqualTo(3)
    }

    @ParameterizedTest
    @ValueSource(strings = ["callback", "reconcile"])
    fun `pending order publishes on its first successful recovery only`(path: String) {
        val user = buyer(0)
        val product = product(100, 5)
        val order = orders.create(user, listOf(OrderItemInput(product, 2))).id

        controlled.delegate = object : PaymentProvider {
            override fun create(providerOrderId: String, amount: Long) = PaymentObservation(
                ObservationKind.PENDING, ProviderTransaction("TX-$providerOrderId", providerOrderId, amount, ProviderStatus.PENDING),
            )
            override fun lookup(providerOrderId: String, expectedTransactionKey: String?, callback: Boolean) = PaymentObservation(
                ObservationKind.SUCCESS, ProviderTransaction("TX-$providerOrderId", providerOrderId, 200, ProviderStatus.SUCCESS),
            )
        }
        orders.confirmWithTestCard(user, order)
        verifyNoInteractions(publisher)

        val providerOrder = "LP-ORD-${order.toString().padStart(10, '0')}"
        if (path == "callback") payments.callback(providerOrder, "TX-$providerOrder") else orders.reconcile(user, order)
        payments.callback(providerOrder, "TX-$providerOrder")
        orders.reconcile(user, order)

        val captured = argumentCaptor<OrderConfirmed>()
        verify(publisher, times(1)).publish(captured.capture())
        assertThat(captured.firstValue.confirmedAt).isEqualTo(orders.getForUser(user, order).confirmedAt!!.toInstant())
        assertThat(captured.firstValue.items).containsExactly(PurchasedItem(product, 2))
        assertThat(stock(product)).isEqualTo(3)
    }

    @Test fun `failure after application event registration rolls back order and suppresses Kafka publication`() {
        val user = buyer(0)
        val product = product(100, 5)
        val order = orders.create(user, listOf(OrderItemInput(product, 2))).id

        doAnswer {
            it.callRealMethod()
            error("DB failure after event registration")
        }.whenever(paymentAttempts).save(any())

        val failure = assertThrows<InvalidDataAccessApiUsageException> { orders.confirmWithTestCard(user, order) }
        assertThat(failure.cause).isInstanceOf(IllegalStateException::class.java)
            .hasMessage("DB failure after event registration")

        verifyNoInteractions(publisher)
        assertThat(orders.getForUser(user, order).status.name).isEqualTo("PAYMENT_PENDING")
        assertThat(number("select count(*) from stock_reservations where order_id=? and status='RESERVED'", order)).isEqualTo(1)
        assertThat(stock(product)).isEqualTo(3)
    }

    @Test
    fun `rollback and publication without a transaction never send`() {
        val event = OrderConfirmed(OrderConfirmed.stableEventId(100), 100, Instant.now(), listOf(PurchasedItem(1, 1)), schemaVersion = 1)

        assertThrows<IllegalStateException> {
            tx {
                events.publishEvent(event)
                error("roll back")
            }
        }
        events.publishEvent(event)

        verifyNoInteractions(publisher)
    }

    @Test
    fun `broker failure cannot roll back confirmed payment or cause PG retry`() {
        val user = buyer(0)
        val product = product(100, 5)
        val order = orders.create(user, listOf(OrderItemInput(product, 2))).id
        doThrow(IllegalStateException("broker unavailable")).whenever(publisher).publish(any())

        assertThat(orders.confirmWithTestCard(user, order).status.name).isEqualTo("CONFIRMED")
        orders.confirmWithTestCard(user, order)

        assertThat(stock(product)).isEqualTo(3)
        verify(publisher, times(1)).publish(any())
        assertThat(number("select count(*) from payment_attempts where order_id=? and status='SUCCESS'", order)).isEqualTo(1)
    }

    @Test
    fun `PG failure never publishes a confirmation`() {
        val user = buyer(0)
        val product = product(100, 5)
        val order = orders.create(user, listOf(OrderItemInput(product, 2))).id

        controlled.delegate = object : PaymentProvider {
            override fun create(providerOrderId: String, amount: Long) = PaymentObservation(
                ObservationKind.FAILED, ProviderTransaction("TX-$providerOrderId", providerOrderId, amount, ProviderStatus.FAILED),
            )
            override fun lookup(providerOrderId: String, expectedTransactionKey: String?, callback: Boolean): PaymentObservation = error("unused")
        }

        assertThat(orders.confirmWithTestCard(user, order).status.name).isEqualTo("PAYMENT_FAILED")

        verifyNoInteractions(publisher)
        assertThat(stock(product)).isEqualTo(5)
    }
}
