package com.loopers.payment

import com.loopers.ControlledPaymentProvider
import com.loopers.SuccessPaymentProvider
import com.loopers.confirmWithTestCard
import com.loopers.testPaymentJson
import com.loopers.application.order.OrderItemInput
import com.loopers.application.payment.PaymentApplicationService
import com.loopers.application.payment.PaymentReadService
import com.loopers.application.product.ProductListInvalidation
import com.loopers.application.transaction.CommerceTransactionTestSupport
import com.loopers.application.transaction.RetryingWriteExecutor
import com.loopers.domain.inventory.StockReservationRepository
import com.loopers.domain.order.OrderRepository
import com.loopers.domain.payment.PaymentAttemptRepository
import com.loopers.domain.payment.ProviderStatus
import com.loopers.domain.product.ProductRepository
import com.loopers.domain.user.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PaymentIntegrationTest : CommerceTransactionTestSupport() {
    @Autowired private lateinit var controlled: ControlledPaymentProvider

    @Autowired private lateinit var context: ApplicationContext
    private lateinit var pg: PgHttpFixture

    @BeforeEach fun startPg() {
        pg = PgHttpFixture()
        controlled.delegate = pg.adapter
    }

    @AfterEach fun stopPg() {
        controlled.delegate = SuccessPaymentProvider()
        pg.close()
    }
    private fun setup(quantity: Int = 2): Triple<Long, Long, Long> {
        val buyer = buyer(10_000)
        val product = product(1000, 5)
        val order = orders.create(buyer, listOf(OrderItemInput(product, quantity))).id
        return Triple(buyer, product, order)
    }
    private fun callback(order: Long, key: String = "TX-1", claimed: String = "SUCCESS") = mvc.perform(
        post("/api/v1/payments/callback")
        .contentType(MediaType.APPLICATION_JSON).content("""{"providerOrderId":"LP-ORD-${order.toString().padStart(10, '0')}","transactionKey":"$key","status":"$claimed"}"""),
    )
    private fun assertState(user: Long, product: Long, order: Long, state: String, remaining: Long, reservation: String) {
        tx {
            em.flush()
            em.clear()
        }
        assertThat(pg.errors).isEmpty()
        assertThat(orders.getForUser(user, order).status.name).isEqualTo(state)
        assertThat(stock(product)).isEqualTo(remaining)
        assertThat(balance(user)).isEqualTo(10_000)
        assertThat(jdbc.queryForObject("select status from stock_reservations where order_id=?", String::class.java, order)).isEqualTo(reservation)
        assertThat(number("select count(*) from payment_attempts where order_id=?", order)).isEqualTo(1)
        println(
            "PAYMENT_RESULT order=$order status=$state create=${pg.creates.get()} externalTransactions=${pg.transactions.size} " +
            "available=${stock(product)} reservation=$reservation points=${balance(user)}",
        )
    }

    @Test
    fun `pending order survives brand deletion and success does not subtract administrator available stock`() {
        val (user, product, order) = setup()
        orders.confirmWithTestCard(user, order)
        products.adjustStock(product, 10)
        val brand = number("select brand_id from products where id=?", product)
        brands.delete(brand)
        pg.complete(ProviderStatus.SUCCESS)
        orders.reconcile(user, order)
        assertState(user, product, order, "CONFIRMED", 10, "CONSUMED")
        assertThat(number("select count(*) from products where id=? and deleted_at is not null", product)).isEqualTo(1)
    }

    @Test
    fun `unproven 422 and empty lookup never release or send again`() {
        val (user, product, order) = setup()
        pg.createMode = "BAD_422"
        assertThat(orders.confirmWithTestCard(user, order).paymentAttempt!!.status.name).isEqualTo("UNKNOWN")
        orders.reconcile(user, order)
        orders.confirmWithTestCard(user, order)
        assertState(user, product, order, "PAYMENT_PENDING", 3, "RESERVED")
        assertThat(pg.transactions).isEmpty()
        assertThat(pg.creates.get()).isEqualTo(1)
    }

    @Test
    fun `reservation return overflow rolls back until administrator adjusts available stock`() {
        val (user, product, order) = setup()
        orders.confirmWithTestCard(user, order)
        products.adjustStock(product, Int.MAX_VALUE)
        pg.complete(ProviderStatus.FAILED)
        assertThrows<ArithmeticException> { orders.reconcile(user, order) }
        assertState(user, product, order, "PAYMENT_PENDING", Int.MAX_VALUE.toLong(), "RESERVED")
        products.adjustStock(product, 3)
        orders.reconcile(user, order)
        assertState(user, product, order, "PAYMENT_FAILED", 5, "RELEASED")
        assertThat(pg.creates.get()).isEqualTo(1)
    }

    @Test
    fun `well formed mismatched create becomes unknown then authority lookup recovers`() {
        val (user, product, order) = setup()
        val providerOrder = "LP-ORD-${order.toString().padStart(10, '0')}"
        pg.createResponse = """{"transactionKey":"TX-1","orderId":"$providerOrder","amount":9999,"status":"SUCCESS"}"""
        assertThat(orders.confirmWithTestCard(user, order).paymentAttempt!!.status.name).isEqualTo("UNKNOWN")
        assertState(user, product, order, "PAYMENT_PENDING", 3, "RESERVED")
        pg.complete(ProviderStatus.SUCCESS)
        orders.reconcile(user, order)
        assertState(user, product, order, "CONFIRMED", 3, "CONSUMED")
        assertThat(pg.creates.get()).isEqualTo(1)
    }

    @Test
    fun `Unicode transaction keys survive HTTP encoding and DB reload`() {
        val (user, product, order) = setup()
        orders.confirmWithTestCard(user, order)
        // Simulate response loss before a transaction key was bound, then discover the provider's Unicode key.
        jdbc.update("update payment_attempts set transaction_key=null where order_id=?", order)
        val t = pg.transactions.remove("TX-1")!!
        pg.transactions["TEST-거래-42"] = t.copy(transactionKey = "TEST-거래-42", status = ProviderStatus.SUCCESS)
        orders.reconcile(user, order)
        assertState(user, product, order, "CONFIRMED", 3, "CONSUMED")
        assertThat(orders.getForUser(user, order).paymentAttempt!!.transactionKey).isEqualTo("TEST-거래-42")
    }

    @Test
    fun `bad callback input is 400 and rejected stock or ownership never sends PG request`() {
        val (user, product, order) = setup(quantity = 6)
        mvc.perform(
            post("/api/v1/payments/callback").contentType(MediaType.APPLICATION_JSON)
            .content("""{"providerOrderId":"x","transactionKey":"","status":"SUCCESS"}"""),
        )
            .andExpect(status().isBadRequest)
        val other = buyer(0)
        mvc.perform(
            post("/api/v1/orders/$order/confirm").header("X-USER-ID", other)
            .contentType(MediaType.APPLICATION_JSON).content(testPaymentJson(order)),
        ).andExpect(status().isForbidden)
        assertThrows<com.loopers.domain.product.InsufficientStockException> { orders.confirmWithTestCard(user, order) }
        assertThat(pg.creates.get()).isZero()
        assertThat(stock(product)).isEqualTo(5)
        assertThat(number("select count(*) from stock_reservations")).isZero()
        assertThat(number("select count(*) from payment_attempts")).isZero()
    }

    @Test
    fun `invalid callback after terminal success does not poison later valid callback`() {
        val (user, product, order) = setup()
        orders.confirmWithTestCard(user, order)
        pg.complete(ProviderStatus.SUCCESS)
        orders.reconcile(user, order)
        pg.transactions["OTHER"] = pg.transactions.getValue("TX-1").copy(transactionKey = "OTHER", orderId = "OTHER-ORDER")
        callback(order, "OTHER").andExpect(status().isConflict)
        val issues = number("select count(*) from payment_issues where order_id=?", order)
        callback(order).andExpect(status().isOk)
        assertThat(number("select count(*) from payment_issues where order_id=?", order)).isEqualTo(issues)
        pg.complete(ProviderStatus.FAILED)
        callback(order).andExpect(status().isConflict)
        assertState(user, product, order, "CONFIRMED", 3, "CONSUMED")
    }

    @Test fun `pending then success consumes once and point balance is unchanged`() {
        val (user, product, order) = setup()
        mvc.perform(post("/api/v1/orders/$order/confirm").header("X-USER-ID", user).contentType(MediaType.APPLICATION_JSON).content(testPaymentJson(order)))
            .andExpect(status().isAccepted)
        assertState(user, product, order, "PAYMENT_PENDING", 3, "RESERVED")
        pg.complete(ProviderStatus.SUCCESS)
        mvc.perform(post("/api/v1/orders/$order/payment/reconcile").header("X-USER-ID", user)).andExpect(status().isOk)
        callback(order).andExpect(status().isOk)
        orders.confirmWithTestCard(user, order)
        assertState(user, product, order, "CONFIRMED", 3, "CONSUMED")
        assertThat(pg.creates.get()).isEqualTo(1)
        assertThat(pg.transactions).hasSize(1)
    }

    @Test fun `authoritative rejection has no external effect and restores reservation once`() {
        val (user, product, order) = setup()
        pg.createMode = "REJECT"
        assertThat(orders.confirmWithTestCard(user, order).status.name).isEqualTo("PAYMENT_FAILED")
        repeat(3) { orders.confirmWithTestCard(user, order) }
        assertState(user, product, order, "PAYMENT_FAILED", 5, "RELEASED")
        assertThat(pg.transactions).isEmpty()
        assertThat(pg.creates.get()).isEqualTo(1)
        assertThat(orders.getForUser(user, order).paymentAttempt!!.transactionKey).isNull()
    }

    @Test fun `response loss creates once preserves unknown and lookup recovers same transaction`() {
        val (user, product, order) = setup()
        pg.createMode = "LOST"
        assertThat(orders.confirmWithTestCard(user, order).paymentAttempt!!.status.name).isEqualTo("UNKNOWN")
        repeat(3) { orders.confirmWithTestCard(user, order) }
        assertState(user, product, order, "PAYMENT_PENDING", 3, "RESERVED")
        pg.complete(ProviderStatus.SUCCESS)
        orders.reconcile(user, order)
        assertState(user, product, order, "CONFIRMED", 3, "CONSUMED")
        assertThat(pg.creates.get()).isEqualTo(1)
        assertThat(pg.transactions).hasSize(1)
    }

    @Test fun `same attempt concurrent confirms send once and different keys conflict`() {
        val (user, product, order) = setup()
        assertOutcomes(
            race(
                (1..6).map {
                    "$it" to {
            orders.confirmWithTestCard(user, order)
            Unit
        }
                },
            ),
            "SUCCESS" to 6,
        )
        val bad = testPaymentJson(order).replace("order-$order", "new-key")
        mvc.perform(post("/api/v1/orders/$order/confirm").header("X-USER-ID", user).contentType(MediaType.APPLICATION_JSON).content(bad)).andExpect(status().isConflict)
        assertState(user, product, order, "PAYMENT_PENDING", 3, "RESERVED")
        assertThat(pg.creates.get()).isEqualTo(1)
    }

    @Test fun `same global key for another order rolls back without extra reservation or POST`() {
        val (user, product, first) = setup()
        val second = orders.create(user, listOf(OrderItemInput(product, 1))).id
        orders.confirmWithTestCard(user, first)
        mvc.perform(post("/api/v1/orders/$second/confirm").header("X-USER-ID", user).contentType(MediaType.APPLICATION_JSON).content(testPaymentJson(first)))
            .andExpect(status().isConflict)
        assertThat(stock(product)).isEqualTo(3)
        assertThat(number("select count(*) from stock_reservations where order_id=?", second)).isZero()
        assertThat(pg.creates.get()).isEqualTo(1)
    }

    @Test fun `concurrent callbacks and reconcile return failed reservation once`() {
        val (user, product, order) = setup()
        orders.confirmWithTestCard(user, order)
        pg.complete(ProviderStatus.FAILED)
        assertOutcomes(
            race(
                (1..6).map {
                    "$it" to {
            if (it % 2 == 0) callback(order).andExpect(status().isOk) else orders.reconcile(user, order)
            Unit
        }
                },
            ),
            "SUCCESS" to 6,
        )
        assertState(user, product, order, "PAYMENT_FAILED", 5, "RELEASED")
        assertThat(pg.creates.get()).isEqualTo(1)
    }

    @Test fun `callback wins before create response and late pending cannot downgrade it`() {
        pg.close()
        pg = PgHttpFixture(responseTimeoutMillis = 4000)
        controlled.delegate = pg.adapter
        val (user, product, order) = setup()
        val created = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        pg.afterCreate = {
            created.countDown()
            check(release.await(10, TimeUnit.SECONDS))
        }
        try {
            val future = pool.submit<com.loopers.application.order.OrderResult> { orders.confirmWithTestCard(user, order) }
            check(created.await(5, TimeUnit.SECONDS))
            pg.complete(ProviderStatus.SUCCESS)
            callback(order).andExpect(status().isOk)
            release.countDown()
            assertThat(future.get(5, TimeUnit.SECONDS).status.name).isEqualTo("CONFIRMED")
            assertState(user, product, order, "CONFIRMED", 3, "CONSUMED")
        } finally {
            release.countDown()
            pool.shutdownNow()
            check(pool.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun `DB apply failure rolls back only local result and same external success can be applied again`() {
        val (user, product, order) = setup()
        orders.confirmWithTestCard(user, order)
        pg.complete(ProviderStatus.SUCCESS)
        var fail = true
        doAnswer { invocation ->
            val result = invocation.callRealMethod()
            if (fail) {
                fail = false
                throw IllegalStateException("injected local apply failure")
            }
            result
        }.whenever(orderStorage).savePaymentState(any())
        assertThrows<org.springframework.dao.InvalidDataAccessApiUsageException> { orders.reconcile(user, order) }
        assertState(user, product, order, "PAYMENT_PENDING", 3, "RESERVED")
        orders.reconcile(user, order)
        assertState(user, product, order, "CONFIRMED", 3, "CONSUMED")
        assertThat(pg.creates.get()).isEqualTo(1)
    }

    @Test fun `process stop after dispatch record survives new service and never resends`() {
        val (user, product, order) = setup()
        controlled.delegate = object : com.loopers.domain.payment.PaymentProvider {
            override fun create(providerOrderId: String, amount: Long): com.loopers.domain.payment.PaymentObservation = error("process stopped before HTTP")
            override fun lookup(providerOrderId: String, expectedTransactionKey: String?, callback: Boolean) = pg.adapter.lookup(providerOrderId, expectedTransactionKey, callback)
        }
        assertThrows<IllegalStateException> { orders.confirmWithTestCard(user, order) }
        controlled.delegate = pg.adapter
        val service = PaymentApplicationService(
            context.getBean(OrderRepository::class.java), context.getBean(PaymentAttemptRepository::class.java),
            context.getBean(StockReservationRepository::class.java), context.getBean(ProductRepository::class.java), context.getBean(UserRepository::class.java),
            controlled, context.getBean(RetryingWriteExecutor::class.java), context.getBean(ProductListInvalidation::class.java), context.getBean(PaymentReadService::class.java),
            context,
        )
        service.confirm(user, order, com.loopers.testPaymentInput(order))
        service.reconcile(user, order)
        assertState(user, product, order, "PAYMENT_PENDING", 3, "RESERVED")
        assertThat(pg.creates.get()).isZero()
    }

    @Test fun `multiple transactions stay quarantined even after one later disappears`() {
        val (user, product, order) = setup()
        orders.confirmWithTestCard(user, order)
        pg.duplicate()
        orders.reconcile(user, order)
        pg.transactions.remove("DUPLICATE")
        pg.complete(ProviderStatus.SUCCESS)
        orders.reconcile(user, order)
        assertState(user, product, order, "PAYMENT_PENDING", 3, "RESERVED")
        assertThat(orders.getForUser(user, order).paymentAttempt!!.quarantined).isTrue()
        assertThat(number("select count(*) from payment_issues where order_id=?", order)).isEqualTo(1)
    }

    @Test fun `forged success callback cannot confirm pending or amount mismatched transaction`() {
        val (user, product, order) = setup()
        orders.confirmWithTestCard(user, order)
        callback(order).andExpect(status().isAccepted)
        val t = pg.transactions.getValue("TX-1")
        pg.transactions["TX-1"] = t.copy(amount = 9999, status = ProviderStatus.SUCCESS)
        callback(order).andExpect(status().isConflict)
        assertState(user, product, order, "PAYMENT_PENDING", 3, "RESERVED")
        assertThat(number("select count(*) from payment_issues where order_id=?", order)).isEqualTo(1)
    }

    @Test fun `reserved deleted product completes or returns without exposing it`() {
        val (user, product, order) = setup()
        orders.confirmWithTestCard(user, order)
        products.adjustStock(product, 10)
        products.delete(product)
        pg.complete(ProviderStatus.FAILED)
        orders.reconcile(user, order)
        assertState(user, product, order, "PAYMENT_FAILED", 12, "RELEASED")
        assertThat(number("select count(*) from products where id=? and deleted_at is not null", product)).isEqualTo(1)
    }

    @Test fun `PG HTTP holds neither Spring transaction nor product database lock`() {
        val (user, product, order) = setup()
        pg.afterCreate = {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
            dataSource.connection.use { connection ->
                connection.autoCommit = false
                try {
                    connection.createStatement().use { it.executeQuery("select id from products where id=$product for update").close() }
                } finally {
                    connection.rollback()
                }
            }
        }
        orders.confirmWithTestCard(user, order)
        assertState(user, product, order, "PAYMENT_PENDING", 3, "RESERVED")
    }

    @Test fun `lookup outage and open circuit retain reservation then recovery resumes`() {
        val (user, product, order) = setup()
        orders.confirmWithTestCard(user, order)
        pg.getCode = 503
        repeat(3) { orders.reconcile(user, order) }
        assertThat(pg.lookups.get()).isEqualTo(4)
        assertState(user, product, order, "PAYMENT_PENDING", 3, "RESERVED")
        pg.getCode = 200
        pg.complete(ProviderStatus.SUCCESS)
        pg.circuit.transitionToHalfOpenState()
        orders.reconcile(user, order)
        assertState(user, product, order, "CONFIRMED", 3, "CONSUMED")
        assertThat(pg.creates.get()).isEqualTo(1)
    }
}
