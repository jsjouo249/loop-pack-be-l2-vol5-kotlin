package com.loopers.application.transaction

import com.loopers.application.brand.BrandApplicationService
import com.loopers.application.order.OrderApplicationService
import com.loopers.application.order.OrderItemInput
import com.loopers.application.point.PointApplicationService
import com.loopers.application.product.ProductApplicationService
import com.loopers.application.user.UserInitializationService
import com.loopers.domain.commerce.CommerceException
import com.loopers.domain.product.InsufficientStockException
import com.loopers.infrastructure.common.JpaWriteOperations
import com.loopers.infrastructure.brand.BrandEntityRepository
import com.loopers.infrastructure.order.OrderEntityRepository
import com.loopers.infrastructure.point.PointAccountEntityRepository
import com.loopers.infrastructure.product.ProductEntityRepository
import com.loopers.utils.DatabaseCleanUp
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@SpringBootTest(properties = ["spring.jpa.show-sql=false"])
@AutoConfigureMockMvc
@Timeout(60)
@Import(WriteObservationConfiguration::class, SqlObservationConfiguration::class, com.loopers.PaymentTestConfiguration::class)
abstract class CommerceTransactionTestSupport {
    @Autowired protected lateinit var brands: BrandApplicationService

    @Autowired protected lateinit var products: ProductApplicationService

    @Autowired protected lateinit var orders: OrderApplicationService

    @Autowired protected lateinit var points: PointApplicationService

    @Autowired protected lateinit var users: UserInitializationService

    @Autowired protected lateinit var jdbc: JdbcTemplate

    @Autowired protected lateinit var em: EntityManager

    @Autowired protected lateinit var manager: PlatformTransactionManager

    @Autowired protected lateinit var dataSource: DataSource

    @Autowired protected lateinit var mvc: MockMvc

    @Autowired protected lateinit var observer: RecordingWriteObserver

    @Autowired private lateinit var cleanup: DatabaseCleanUp

    @MockitoSpyBean protected lateinit var productStorage: ProductEntityRepository

    @MockitoSpyBean protected lateinit var pointStorage: PointAccountEntityRepository

    @MockitoSpyBean protected lateinit var orderStorage: OrderEntityRepository

    @MockitoSpyBean protected lateinit var brandStorage: BrandEntityRepository

    @MockitoSpyBean protected lateinit var retryTime: RetryTime

    @MockitoSpyBean protected lateinit var writeOperations: JpaWriteOperations

    @BeforeEach
    fun resetObservations() = observer.events.clear()

    @AfterEach
    fun cleanup() {
        println("WRITE_OBSERVATIONS ${observer.events}")
        cleanup.truncateAllTables()
    }

    protected fun <T : Any> tx(action: () -> T): T = TransactionTemplate(manager).execute { action() }!!

    protected fun buyer(balance: Long): Long = users.create("구매자").id.also { if (balance > 0) points.charge(it, balance) }

    protected fun product(price: Long, stock: Int): Long = products.create(brands.create("브랜드").id, "상품", price, stock).id

    protected fun draft(userId: Long, productId: Long): Long = orders.create(userId, listOf(OrderItemInput(productId, 1))).id

    protected fun number(sql: String, vararg arguments: Any): Long = jdbc.queryForObject(sql, Long::class.java, *arguments)!!

    protected fun stock(id: Long): Long = number("select stock from products where id = ?", id)

    protected fun balance(id: Long): Long = number("select balance from point_balances where user_id = ?", id)

    /**
     * 초기 데이터를 커밋한 뒤 호출하며, 작업별 스레드에서 서비스의 독립 트랜잭션을 실행한다.
     * 시작 시점만 맞추고 읽기 이후에는 장벽을 두지 않아 실제 서비스의 잠금 경합을 검증한다.
     */
    protected fun race(actions: List<Pair<String, () -> Unit>>): List<RequestOutcome> {
        val pool = Executors.newFixedThreadPool(actions.size)
        val ready = CountDownLatch(actions.size)
        val start = CountDownLatch(1)
        try {
            val futures = actions.map { (id, action) ->
                pool.submit<RequestOutcome> {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS))
                    try {
                        action()
                        RequestOutcome(id, "SUCCESS")
                    } catch (failure: CommerceException) {
                        RequestOutcome(id, failure.reason.name)
                    } catch (_: InsufficientStockException) {
                        RequestOutcome(id, "INSUFFICIENT_STOCK")
                    } catch (failure: Exception) {
                        // 기술 오류도 요청별 결과에 포함해 성공·업무 거절 집계에서 누락되지 않게 한다.
                        RequestOutcome(id, "TECHNICAL:${failure.javaClass.simpleName}")
                    }
                }
            }
            check(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            return futures.map { it.get(30, TimeUnit.SECONDS) }.also { println("REQUEST_OUTCOMES $it") }
        } finally {
            // 준비나 실행 중 실패해도 대기 중인 작업을 풀고 제한 시간 안에 스레드를 정리한다.
            start.countDown()
            pool.shutdownNow()
            check(pool.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    protected fun assertOutcomes(actual: List<RequestOutcome>, vararg expected: Pair<String, Int>) {
        assertThat(actual.groupingBy { it.outcome }.eachCount()).isEqualTo(expected.toMap())
    }
}

data class RequestOutcome(val id: String, val outcome: String)
data class WriteObservation(val kind: String, val requestId: String, val operation: String, val attempt: Int, val outcome: String)

class RecordingWriteObserver : WriteObserver() {
    val events = CopyOnWriteArrayList<WriteObservation>()
    override fun attempt(requestId: String, operation: String, number: Int, outcome: String) {
        events.add(WriteObservation("attempt", requestId, operation, number, outcome))
        super.attempt(requestId, operation, number, outcome)
    }
    override fun request(requestId: String, operation: String, attempts: Int, outcome: String) {
        events.add(WriteObservation("request", requestId, operation, attempts, outcome))
        super.request(requestId, operation, attempts, outcome)
    }
}

@TestConfiguration
class WriteObservationConfiguration {
    @Bean
    @Primary
    fun recordingWriteObserver() = RecordingWriteObserver()
}
