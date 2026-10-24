package com.loopers.application.transaction

import com.loopers.confirmWithTestCard
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.anyVararg
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MySqlTransactionPolicyTest : CommerceTransactionTestSupport() {
    @Autowired private lateinit var executor: RetryingWriteExecutor

    @Test
    fun `pool sessions use two second lock waits and matched rows`() {
        dataSource.connection.use { first ->
            dataSource.connection.use { second ->
                for (connection in listOf(first, second)) {
                    connection.createStatement().use { statement ->
                        statement.executeQuery("select @@session.innodb_lock_wait_timeout, version()").use { rows ->
                            check(rows.next())
                            assertThat(rows.getInt(1)).isEqualTo(2)
                            assertThat(rows.getString(2)).startsWith("8.0.")
                            println("MYSQL_RUNTIME version=${rows.getString(2)} driver=${connection.metaData.driverVersion} lockWait=${rows.getInt(1)}")
                        }
                    }
                }
            }
        }
        val product = product(1000, 5)
        assertThat(jdbc.update("update products set stock=5 where id=? and deleted_at is null", product)).isEqualTo(1)
    }

    @Test
    fun `real lock timeout after product SQL rolls back whole order and returns 503 without retry`() {
        val buyer = buyer(10_000)
        val product = product(1000, 5)
        val order = draft(buyer, product)
        val marker = brands.create("잠금 대상").id
        dataSource.connection.use { holder ->
            holder.autoCommit = false
            try {
                holder.prepareStatement("select id from brands where id=? for update").use {
                    it.setLong(1, marker)
                    it.executeQuery().close()
                }
                doAnswer { invocation ->
                    em.flush()
                    // 실제 재고 변경 SQL이 반영된 뒤 다음 저장 경계에서 잠금 대기 실패를 유도한다.
                    assertThat(stock(product)).isEqualTo(4)
                    jdbc.update("update brands set name='blocked' where id=?", marker)
                    invocation.callRealMethod()
                }.whenever(orderStorage).savePaymentState(any())
                observer.events.clear()
                mvc.perform(post("/api/v1/orders/$order/confirm").header("X-USER-ID", buyer).contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(com.loopers.testPaymentJson(order)))
                    .andExpect(status().isServiceUnavailable)
                    .andExpect(jsonPath("$.meta.errorCode").value("TRANSACTION_BUSY"))
                    .andExpect(jsonPath("$.meta.message").value("다른 요청을 처리 중입니다. 잠시 후 상태를 확인해 주세요."))
                    .andExpect(jsonPath("$.data").doesNotExist())
                assertThat(observer.events.filter { it.kind == "attempt" }.map { it.outcome }).containsExactly("LOCK_TIMEOUT")
            } finally {
                holder.rollback()
            }
        }
        // 유스케이스 종료 후 새 트랜잭션에서 앞선 재고 SQL까지 롤백됐는지 확인한다.
        tx {
            em.flush()
            em.clear()
            assertThat(stock(product)).isEqualTo(5)
            assertThat(balance(buyer)).isEqualTo(10_000)
            val saved = orders.getForUser(buyer, order)
            assertThat(saved.status.name).isEqualTo("DRAFT")
            assertThat(saved.paymentAmount).isNull()
            assertThat(saved.paymentResult).isNull()
            assertThat(saved.confirmedAt).isNull()
            assertThat(saved.items.single().unitPrice).isEqualTo(1000)
        }
    }

    @Test
    fun `real deadlock retries charging from newly committed balance`() {
        val buyer = buyer(10_000)
        val calls = AtomicInteger()
        var failedConnection: java.sql.Connection? = null
        doAnswer { invocation ->
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.getResourceMap()).isEmpty()
            assertThat(failedConnection!!.isClosed).isTrue()
            invocation.callRealMethod()
        }.whenever(retryTime).sleep(50)
        withDeadlock(
            install = { collide ->
                doAnswer { invocation ->
                    val result = invocation.callRealMethod()
                    if (calls.incrementAndGet() == 1) {
                        // JdbcTemplate의 닫기 억제 프록시가 아닌 실제 풀 연결의 반환 여부를 확인한다.
                        failedConnection = jdbc.execute(
                            org.springframework.jdbc.core.ConnectionCallback {
                            (it as org.springframework.jdbc.datasource.ConnectionProxy).targetConnection
                        },
                        )
                        collide()
                    }
                    result
                }.whenever(pointStorage).saveBalance(any())
            },
            antagonist = { jdbc.update("update point_balances set balance=balance+500 where user_id=?", buyer) },
            action = { points.charge(buyer, 2000) },
        )
        assertThat(calls.get()).isEqualTo(2)
        assertThat(balance(buyer)).isEqualTo(12_500)
        assertRetry("point.charge")
    }

    @Test
    fun `real deadlock retries entire order with snapshots and one PG dispatch`() {
        val buyer = buyer(10_000)
        val product = product(1000, 5)
        val order = draft(buyer, product)
        val calls = AtomicInteger()
        withDeadlock(
            install = { collide ->
                doAnswer { invocation ->
                    val result = invocation.callRealMethod()
                    if (calls.incrementAndGet() == 1) collide()
                    result
                }.whenever(orderStorage).savePaymentState(any())
            },
            antagonist = { jdbc.update("update products set stock=stock+1 where id=?", product) },
            action = { orders.confirmWithTestCard(buyer, order) },
        )
        assertThat(balance(buyer)).isEqualTo(10_000)
        assertThat(stock(product)).isEqualTo(5)
        assertThat(orders.getForUser(buyer, order).paymentAmount).isEqualTo(1000)
        assertRetry("payment.prepare")
    }

    @Test
    fun `real deadlock retries brand deletion and includes a newly committed child`() {
        val brand = brands.create("브랜드").id
        products.create(brand, "첫 상품", 1000, 1)
        val calls = AtomicInteger()
        withDeadlock(
            install = { collide ->
                doAnswer { invocation ->
                    val result = invocation.callRealMethod()
                    if (invocation.getArgument<String>(0).startsWith("update products set deleted_at") && calls.incrementAndGet() == 1) collide()
                    result
                }.whenever(writeOperations).update(any(), anyVararg())
            },
            antagonist = {
                jdbc.update("update brands set name='선행 변경' where id=?", brand)
                jdbc.update(
                    "insert into products(brand_id,name,price,stock,created_at,updated_at) values (?, '새 상품', 1000, 0, now(6), now(6))",
                    brand,
                )
            },
            action = { brands.delete(brand) },
        )
        assertThat(number("select count(*) from products where brand_id=?", brand)).isEqualTo(2)
        assertThat(number("select count(*) from products where brand_id=? and deleted_at is null", brand)).isZero()
        assertThat(number("select count(*) from brands where id=? and deleted_at is not null", brand)).isEqualTo(1)
        assertRetry("brand.delete")
    }

    @Test
    fun `five second transaction timeout cancels real driver query and rolls back without retry`() {
        val product = product(1000, 5)
        observer.events.clear()
        val started = System.nanoTime()
        val failure = assertThrows<Exception> {
            executor.execute("timeout.probe") {
                jdbc.update("update products set stock=4 where id=?", product)
                em.createNativeQuery("select sleep(7)").singleResult
            }
        }
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertThat(failure).isNotInstanceOf(TransactionBusyException::class.java)
        assertThat(elapsed).isBetween(3500L, 12_000L)
        // 연결 폐기로 롤백 오류가 대표 예외가 되어도 보존된 최초 timeout 원인까지 확인한다.
        val failures = generateSequence<Throwable>(failure) { it.cause }.toList() +
            failure.suppressed.flatMap { generateSequence(it) { cause -> cause.cause }.toList() }
        assertThat(failures).anyMatch {
            it is jakarta.persistence.QueryTimeoutException || it is org.hibernate.QueryTimeoutException ||
                it is java.sql.SQLTimeoutException
        }
        tx {
            assertThat(number("select stock from products where id=? for update", product)).isEqualTo(5)
        }
        assertThat(observer.events.filter { it.kind == "attempt" }.map { it.outcome }).containsExactly("UNKNOWN_COMPLETION")
        println("TRANSACTION_TIMEOUT elapsedMs=$elapsed exception=${failure.javaClass.name}")
    }

    @Test
    fun `retry exhaustion returns 503 and each injected deadlock rolls back real SQL`() {
        val buyer = buyer(10_000)
        doAnswer { invocation ->
            invocation.callRealMethod()
            assertThat(balance(buyer)).isEqualTo(12_000)
            throw java.sql.SQLException("injected driver deadlock", "40001", 1213)
        }.whenever(pointStorage).saveBalance(any())
        observer.events.clear()
        mvc.perform(
            post("/api/v1/points/charge").header("X-USER-ID", buyer)
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("""{"amount":2000}"""),
        )
            .andExpect(status().isServiceUnavailable).andExpect(jsonPath("$.meta.errorCode").value("TRANSACTION_BUSY"))
        assertThat(balance(buyer)).isEqualTo(10_000)
        assertThat(observer.events.filter { it.kind == "attempt" }.map { it.outcome }).containsExactly("DEADLOCK", "DEADLOCK")
        assertThat(observer.events.count { it.kind == "request" }).isEqualTo(1)
    }

    @Test
    fun `retry sees committed business change and preserves existing 409 envelope`() {
        val buyer = buyer(10_000)
        val product = product(1000, 5)
        val order = draft(buyer, product)
        val calls = AtomicInteger()
        doAnswer { invocation ->
            val result = invocation.callRealMethod()
            if (calls.incrementAndGet() == 1) throw java.sql.SQLException("injected driver deadlock", "40001", 1213)
            result
        }.whenever(orderStorage).savePaymentState(any())
        doAnswer {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
            jdbc.update("update products set stock=0 where id=?", product)
            Unit
        }.whenever(retryTime).sleep(50)
        observer.events.clear()
        mvc.perform(post("/api/v1/orders/$order/confirm").header("X-USER-ID", buyer).contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(com.loopers.testPaymentJson(order)))
            .andExpect(status().isConflict).andExpect(jsonPath("$.meta.errorCode").value("INSUFFICIENT_STOCK"))
        assertThat(stock(product)).isZero()
        assertThat(balance(buyer)).isEqualTo(10_000)
        assertThat(orders.getForUser(buyer, order).status.name).isEqualTo("DRAFT")
        assertThat(observer.events.filter { it.kind == "attempt" }.map { it.outcome }).containsExactly("DEADLOCK", "BUSINESS_REJECTION")
    }

    @Test
    fun `connection ambiguity is a 500 without retry even after prior SQL`() {
        val buyer = buyer(10_000)
        doAnswer { invocation ->
            invocation.callRealMethod()
            throw java.sql.SQLException("simulated connection outcome unknown", "08007", 0)
        }.whenever(pointStorage).saveBalance(any())
        observer.events.clear()
        mvc.perform(
            post("/api/v1/points/charge").header("X-USER-ID", buyer)
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("""{"amount":2000}"""),
        )
            .andExpect(status().isInternalServerError)
        assertThat(observer.events.filter { it.kind == "attempt" }.map { it.outcome }).containsExactly("UNKNOWN_COMPLETION")
        assertThat(balance(buyer)).isEqualTo(10_000)
    }

    private fun assertRetry(operation: String) {
        val events = observer.events.filter { it.operation == operation }
        assertThat(events.filter { it.kind == "attempt" }.map { it.outcome }).containsExactly("DEADLOCK", "SUCCESS")
        assertThat(events.filter { it.kind == "request" }.map { it.outcome }).containsExactly("SUCCESS")
        assertThat(events.last().attempt).isEqualTo(2)
    }

    // 테스트용 상대 트랜잭션만 잠금 순서를 뒤집어 교착을 만든다.
    // 상대가 더 많은 행을 실제 변경하게 해 InnoDB가 작은 애플리케이션 트랜잭션을 희생자로 선택하도록 유도한다.
    private fun withDeadlock(install: (() -> Unit) -> Unit, antagonist: () -> Unit, action: () -> Any) {
        val markers = (1..24).map { brands.create("교착-$it").id }
        val locked = CountDownLatch(1)
        val collide = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val other = pool.submit {
                tx {
                    markers.forEach { jdbc.update("update brands set name=concat(name,'x') where id=?", it) }
                    locked.countDown()
                    check(collide.await(10, TimeUnit.SECONDS))
                    antagonist()
                }
            }
            check(locked.await(10, TimeUnit.SECONDS))
            install {
                // 상대는 서비스가 잠근 행을, 서비스는 상대가 잠근 표식 행을 기다리도록 순환 대기를 만든다.
                collide.countDown()
                jdbc.update("update brands set name='victim' where id=?", markers.first())
            }
            observer.events.clear()
            action()
            other.get(30, TimeUnit.SECONDS)
        } finally {
            collide.countDown()
            pool.shutdownNow()
            check(pool.awaitTermination(10, TimeUnit.SECONDS))
        }
    }
}
