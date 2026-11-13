package com.loopers.week7

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.loopers.application.brand.BrandApplicationService
import com.loopers.application.outbox.OutboxRecovery
import com.loopers.application.outbox.OutboxRelay
import com.loopers.application.outbox.OutboxTransactions
import com.loopers.application.point.PointApplicationService
import com.loopers.application.product.ProductApplicationService
import com.loopers.application.user.UserInitializationService
import com.loopers.domain.order.event.OrderConfirmed
import com.loopers.domain.order.event.PurchasedItem
import com.loopers.domain.outbox.BrokerReceipt
import com.loopers.domain.outbox.OrderConfirmedCodec
import com.loopers.domain.outbox.OutboxFact
import com.loopers.domain.outbox.OutboxRepository
import com.loopers.domain.outbox.OutboxSender
import com.loopers.interfaces.consumer.PurchaseActivityConsumer
import com.loopers.payment.PgHttpFixture
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.init.ScriptUtils
import org.springframework.core.io.FileSystemResource
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.listener.AcknowledgingMessageListener
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker
import org.springframework.kafka.test.utils.ContainerTestUtils
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.utility.DockerImageName
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

@Timeout(180)
class OutboxDeliveryE2ETest {
    private val root = Path.of(System.getProperty("week7.root"))
    private val mysql = MySQLContainer(DockerImageName.parse("mysql:8.0"))
    private val redis = GenericContainer(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379)
    private val topic = "outbox-${UUID.randomUUID()}"
    private val group = "purchases-${UUID.randomUUID()}"
    private val broker = EmbeddedKafkaKraftBroker(1, 2, topic)
    private val contexts = mutableListOf<ConfigurableApplicationContext>()
    private val listeners = mutableListOf<ConcurrentMessageListenerContainer<String, ByteArray>>()
    private val received = CopyOnWriteArrayList<Pair<String, Long>>()
    private lateinit var pg: PgHttpFixture
    private lateinit var jdbc: JdbcTemplate
    private lateinit var dataSource: DriverManagerDataSource
    private var baseline: Process? = null
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    @BeforeEach
    fun startResources() {
        mysql.start()
        redis.start()
        broker.afterPropertiesSet()
        pg = PgHttpFixture().also { it.createMode = "SUCCESS" }
        dataSource = DriverManagerDataSource(mysql.jdbcUrl, mysql.username, mysql.password)
        jdbc = JdbcTemplate(dataSource)
    }

    @AfterEach
    fun stopResources() {
        baseline?.let { process ->
            process.destroy()
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                check(process.waitFor(5, TimeUnit.SECONDS))
            }
        }
        listeners.asReversed().forEach { it.stop() }
        contexts.asReversed().forEach { it.close() }
        if (::pg.isInitialized) pg.close()
        broker.destroy()
        redis.stop()
        mysql.stop()
    }

    private fun args(ddl: String) = arrayOf(
        "--spring.profiles.active=test", "--server.port=0", "--spring.jpa.hibernate.ddl-auto=$ddl", "--spring.jpa.show-sql=false",
        "--datasource.mysql-jpa.main.jdbc-url=${mysql.jdbcUrl}", "--datasource.mysql-jpa.main.username=${mysql.username}",
        "--datasource.mysql-jpa.main.password=${mysql.password}", "--datasource.mysql-jpa.main.minimum-idle=1",
        "--datasource.mysql-jpa.main.maximum-pool-size=5", "--spring.kafka.bootstrap-servers=${broker.brokersAsString}",
        "--datasource.redis.master.host=${redis.host}", "--datasource.redis.master.port=${redis.firstMappedPort}",
        "--datasource.redis.replicas[0].host=${redis.host}", "--datasource.redis.replicas[0].port=${redis.firstMappedPort}",
        "--payment.pg.base-url=${pg.properties.baseUrl}", "--purchase.kafka.topic=$topic", "--purchase.kafka.group=$group",
        "--purchase.consumer.auto-startup=false", "--outbox.relay.enabled=false",
        "--demo-kafka.test.topic-name=$topic", "--logging.level.org.apache.kafka=WARN",
    )

    private fun startApi(ddl: String): ConfigurableApplicationContext = SpringApplicationBuilder(OutboxE2eApplication::class.java)
        .run(*args(ddl)).also { contexts.add(it) }

    private fun consumer(context: ConfigurableApplicationContext) {
        val listener = context.getBean(PurchaseActivityConsumer::class.java)

        @Suppress("UNCHECKED_CAST")
        val factory = context.getBean("purchaseListenerFactory") as ConcurrentKafkaListenerContainerFactory<String, ByteArray>
        val container = factory.createContainer(topic).apply {
            containerProperties.groupId = group
            setupMessageListener(
                AcknowledgingMessageListener<String, ByteArray> { record, acknowledgment ->
                listener.consume(record, checkNotNull(acknowledgment))
                val event = context.getBean(ObjectMapper::class.java).readValue(record.value(), OrderConfirmed::class.java)
                received.add(event.eventId to record.offset())
            },
            )
        }
        listeners.add(container)
        container.start()
        ContainerTestUtils.waitForAssignment(container, 2)
    }

    private fun url(context: ConfigurableApplicationContext) = "http://127.0.0.1:${(context as ServletWebServerApplicationContext).webServer.port}"

    private fun post(base: String, path: String, body: String, user: Long? = null): JsonNode {
        val builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body))
        if (user != null) builder.header("X-USER-ID", user.toString())
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) { "HTTP $path status=${response.statusCode()} body=${response.body()}" }

        return com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().readTree(response.body())["data"]
    }

    private fun confirm(base: String, user: Long, first: Long, second: Long): Long {
        val order = post(base, "/api/v1/orders", """{"items":[{"productId":$first,"quantity":2},{"productId":$second,"quantity":1}]}""", user)["id"].asLong()
        post(base, "/api/v1/orders/$order/confirm", """{"paymentAttemptKey":"order-$order","cardType":"TEST","cardNo":"0000-0000-0000-0000"}""", user)

        return order
    }

    private fun number(sql: String, vararg args: Any) = jdbc.queryForObject(sql, Long::class.java, *args)!!

    private fun savedEvent(order: Long): OrderConfirmed {
        val confirmedAt = jdbc.queryForObject("select confirmed_at from orders where id=?", { result, _ ->
            result.getTimestamp(1, Calendar.getInstance(TimeZone.getTimeZone("UTC"))).toInstant()
        }, order)!!
        val items = jdbc.query("select product_id, quantity from order_items where order_id=? order by product_id", { result, _ ->
            PurchasedItem(result.getLong(1), result.getInt(2))
        }, order)

        return OrderConfirmed(OrderConfirmed.stableEventId(order), order, confirmedAt, items, 1)
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "$label timed out; received=$received activities=${jdbc.queryForList("select * from purchase_activities")}" }
            Thread.sleep(25)
        }
    }

    private fun assertActivities(order: Long, first: Long, second: Long) {
        val activities = jdbc.query(
            "select product_id, quantity from purchase_activities where order_id=? order by product_id",
            { result, _ -> result.getLong(1) to result.getInt(2) },
            order,
        )
        assertThat(activities).containsExactly(first to 2, second to 1)
        assertThat(number("select count(*) from purchase_receipts where order_id=?", order)).isEqualTo(1)
        assertThat(number("select count(*) from purchase_event_receipts where order_id=?", order)).isEqualTo(1)
    }

    @Test
    fun `committed outbox survives new relay and broker acknowledged interruption converges across distinct offsets`() {
        val context = startApi("create")
        consumer(context)
        val user = context.getBean(UserInitializationService::class.java).create("W7 buyer").id
        context.getBean(PointApplicationService::class.java).charge(user, 10_000)
        val brand = context.getBean(BrandApplicationService::class.java).create("W7 brand").id
        val products = context.getBean(ProductApplicationService::class.java)
        val first = products.create(brand, "first", 100, 10).id
        val second = products.create(brand, "second", 200, 10).id
        val order = confirm(url(context), user, first, second)
        val id = OrderConfirmed.stableEventId(order)
        val repository = context.getBean(OutboxRepository::class.java)
        val original = checkNotNull(repository.find(id)).fact
        assertThat(number("select count(*) from purchase_activities")).isZero()
        assertThat(number("select count(*) from order_outbox where order_id=? and status='PENDING'", order)).isEqualTo(1)
        products.delete(first)
        val transactions = context.getBean(OutboxTransactions::class.java)
        val sender = context.getBean(OutboxSender::class.java)
        val observed = mutableListOf<BrokerReceipt>()
        val stopped = OutboxRelay(
            transactions,
            object : OutboxSender {
            override fun send(fact: OutboxFact): BrokerReceipt {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
                observed.add(sender.send(fact))
                throw SimulatedStop()
            }
        },
        )

        assertThrows<SimulatedStop> { stopped.runOnce(id) }

        assertThat(observed).hasSize(1)
        assertThat(repository.find(id)!!.status.name).isEqualTo("PENDING")
        context.getBean(MutableOutboxClock::class.java).advance()
        val restarted = OutboxRelay(
            transactions,
            object : OutboxSender {
            override fun send(fact: OutboxFact): BrokerReceipt = sender.send(fact).also { observed.add(it) }
        },
        )
        assertThat(restarted.runOnce(id)).isEqualTo(1)
        await("two actual consumer deliveries") { received.count { it.first == id } >= 2 }
        assertThat(observed.map { it.partition }).containsOnly(observed.first().partition)
        assertThat(observed.map { it.offset }).doesNotHaveDuplicates()
        assertThat(repository.find(id)!!.fact).isEqualTo(original)
        assertThat(repository.find(id)!!.status.name).isEqualTo("PUBLISHED")
        assertThat(repository.find(id)!!.publishedAt).isNotNull()
        assertActivities(order, first, second)
        assertThat(number("select count(*) from stock_reservations where order_id=? and status='CONSUMED'", order)).isEqualTo(2)
        assertThat(number("select stock from products where id=?", first)).isEqualTo(8)
        assertThat(number("select balance from point_balances where user_id=?", user)).isEqualTo(10_000)
        assertThat(pg.creates.get()).isEqualTo(1)
        assertThat(pg.transactions).hasSize(1)
        assertBrokerRecords(context, id, observed.map { it.offset })
        println("WEEK7_E2E orderId=$order eventId=$id offsets=${observed.map { it.offset }} deliveries=${received.count { it.first == id }} activities=2 quantities=2,1 pgCreates=${pg.creates.get()} status=PUBLISHED")
    }

    private fun assertBrokerRecords(context: ConfigurableApplicationContext, eventId: String, offsets: List<Long>) {
        val config = mapOf<String, Any>(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to broker.brokersAsString,
            ConsumerConfig.GROUP_ID_CONFIG to "observe-${UUID.randomUUID()}",
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to ByteArrayDeserializer::class.java,
        )
        KafkaConsumer<String, ByteArray>(config).use { observer ->
            observer.subscribe(listOf(topic))
            val found = mutableListOf<Long>()
            val mapper = context.getBean(ObjectMapper::class.java)
            val deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos()
            while (found.size < offsets.size && System.nanoTime() < deadline) {
                observer.poll(Duration.ofMillis(100)).forEach {
                    if (mapper.readValue(it.value(), OrderConfirmed::class.java).eventId == eventId) found.add(it.offset())
                }
            }
            assertThat(found).containsExactlyElementsOf(offsets)
        }
    }

    /** W6 주문 확정·소비 사실을 보존한 DB에 현재 앱의 추가 DDL만 적용하고, PG 추가 호출 없이 Outbox 복구·재복구 결과를 대조한다. */
    @Test
    fun `actual W6 revision prepares missing and consumed orders then W7 restores without PG calls`() {
        val purchaseContext = SpringApplicationBuilder(PurchaseOnlyConfiguration::class.java).web(WebApplicationType.NONE)
            .run(*args("create"), "--spring.main.web-application-type=none").also { contexts.add(it) }
        consumer(purchaseContext)
        val work = Files.createTempDirectory("week7-w6-")
        val manifest = work.resolve("manifest.txt")
        val log = work.resolve("baseline.log")
        val port = ServerSocket(0).use { it.localPort }
        baseline = ProcessBuilder(
            listOf(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-Xmx384m", "-jar", System.getProperty("week6.fixture.jar")) +
                args("create").filterNot { it.startsWith("--server.port=") } +
                listOf("--server.port=$port", "--fixture-manifest=$manifest"),
        ).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        val deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos()
        while (!Files.exists(manifest)) {
            check(baseline!!.isAlive && System.nanoTime() < deadline) { "W6 startup failed: ${Files.readString(log)}" }
            Thread.sleep(50)
        }
        val (user, first, second) = Files.readString(manifest).split(',').map(String::toLong)
        val base = "http://127.0.0.1:$port"
        val missing = confirm(base, user, first, second)
        val consumed = confirm(base, user, first, second)
        await("W6 consumed case") { received.any { it.first == OrderConfirmed.stableEventId(consumed) } }
        assertThat(number("select count(*) from orders where status='CONFIRMED'")).isEqualTo(2)
        assertThat(number("select count(*) from stock_reservations where status='CONSUMED'")).isEqualTo(4)
        assertThat(number("select count(*) from information_schema.tables where table_schema=database() and table_name='order_outbox'")).isZero()
        assertThat(number("select count(*) from purchase_activities where order_id=?", missing)).isZero()
        assertActivities(consumed, first, second)
        val facts = jdbc.queryForList("select id, confirmed_at from orders order by id")
        val originalEvents = listOf(missing, consumed).associateWith(::savedEvent)
        val reservations = jdbc.queryForList("select * from stock_reservations order by order_id, product_id")
        val attempts = jdbc.queryForList("select order_id, provider_order_id, transaction_key, status from payment_attempts order by order_id")
        val lines = jdbc.queryForList("select * from order_items order by order_id, product_id")
        val stock = jdbc.queryForList("select id, stock from products order by id")
        val creates = pg.creates.get()
        val lookups = pg.lookups.get()
        val externalTransactions = pg.transactions.toMap()
        println("WEEK7_W6_FACTS events=${originalEvents.values}")
        println("WEEK7_W6_PREPARED revision=4dfa06b missing=$missing consumed=$consumed facts=$facts attempts=$attempts items=$lines pgCreates=$creates pgLookups=$lookups transactions=${externalTransactions.size}")
        assertThat(creates).isEqualTo(2)
        baseline!!.destroy()
        check(baseline!!.waitFor(10, TimeUnit.SECONDS))
        baseline = null
        listeners.forEach { it.stop() }
        listeners.clear()
        purchaseContext.close()
        contexts.remove(purchaseContext)
        dataSource.connection.use {
            ScriptUtils.executeSqlScript(it, FileSystemResource(root.resolve("docs/week7/sql/create-order-outbox.sql")))
            // 현재 앱의 schema validation을 위해 신규 쿠폰 테이블도 준비하되 기존 주문·결제·활동 데이터는 유지한다.
            ScriptUtils.executeSqlScript(it, FileSystemResource(root.resolve("docs/week8/schema.sql")))
        }

        val context = startApi("validate")
        consumer(context)
        val recovery = context.getBean(OutboxRecovery::class.java)
        val relay = context.getBean(OutboxRelay::class.java)
        val codec = context.getBean(OrderConfirmedCodec::class.java)
        val repository = context.getBean(OutboxRepository::class.java)
        listOf(missing, consumed).forEach { order ->
            val id = recovery.restore(order)
            val fact = repository.find(id)!!.fact
            val event = codec.decode(fact.payload)
            assertThat(event).isEqualTo(originalEvents.getValue(order))
            assertThat(relay.runOnce(id)).isEqualTo(1)
        }
        await("W7 recovery deliveries") {
            received.count { it.first == OrderConfirmed.stableEventId(missing) } >= 1 &&
                received.count { it.first == OrderConfirmed.stableEventId(consumed) } >= 2
        }
        listOf(missing, consumed).forEach { order ->
            assertActivities(order, first, second)
            val id = recovery.restore(order)
            assertThat(relay.runOnce(id)).isZero()
        }
        assertThat(number("select count(*) from order_outbox")).isEqualTo(2)
        assertThat(number("select count(*) from order_outbox where status='PUBLISHED'")).isEqualTo(2)
        assertThat(jdbc.queryForList("select id, confirmed_at from orders order by id")).isEqualTo(facts)
        assertThat(jdbc.queryForList("select order_id, provider_order_id, transaction_key, status from payment_attempts order by order_id")).isEqualTo(attempts)
        assertThat(jdbc.queryForList("select * from order_items order by order_id, product_id")).isEqualTo(lines)
        assertThat(jdbc.queryForList("select id, stock from products order by id")).isEqualTo(stock)
        assertThat(jdbc.queryForList("select * from stock_reservations order by order_id, product_id")).isEqualTo(reservations)
        assertThat(number("select balance from point_balances where user_id=?", user)).isEqualTo(10_000)
        assertThat(pg.creates.get()).isEqualTo(creates)
        assertThat(pg.lookups.get()).isEqualTo(lookups)
        assertThat(pg.transactions).isEqualTo(externalTransactions)
        assertThat(pg.errors).isEmpty()
        println("WEEK7_W6_RECOVERED missing=$missing consumed=$consumed outbox=2 activities=4 pgCreateDelta=0 pgLookupDelta=0 externalTransactionDelta=0 secondRecoveryDelta=0")
    }

    private class SimulatedStop : Error("Stopped after actual broker acknowledgment, before PUBLISHED TX")
}
