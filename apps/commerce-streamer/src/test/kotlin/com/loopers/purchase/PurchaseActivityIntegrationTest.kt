package com.loopers.purchase

import com.fasterxml.jackson.databind.ObjectMapper
import com.loopers.application.purchase.PurchaseActivityService
import com.loopers.domain.order.event.OrderConfirmed
import com.loopers.domain.order.event.PurchasedItem
import com.loopers.domain.purchase.PurchaseActivityRepository
import com.loopers.domain.purchase.PurchaseEventConflict
import com.loopers.infrastructure.purchase.PurchaseActivityId
import com.loopers.infrastructure.purchase.PurchaseActivityJpaEntity
import com.loopers.interfaces.consumer.PurchaseActivityConsumer
import jakarta.persistence.EntityManager
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.reset
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.InvalidDataAccessApiUsageException
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.listener.AcknowledgingMessageListener
import org.springframework.kafka.test.EmbeddedKafkaBroker
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.UncategorizedSQLException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@SpringBootTest(properties = ["purchase.consumer.auto-startup=false", "spring.jpa.show-sql=false"])
@EmbeddedKafka(partitions = 2, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@Timeout(90)
class PurchaseActivityIntegrationTest {
    @Autowired private lateinit var service: PurchaseActivityService

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var manager: PlatformTransactionManager

    @Autowired private lateinit var em: EntityManager

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var broker: EmbeddedKafkaBroker

    @Autowired private lateinit var listener: PurchaseActivityConsumer

    @Autowired private lateinit var purchaseListenerFactory: ConcurrentKafkaListenerContainerFactory<String, ByteArray>

    @MockitoSpyBean private lateinit var repository: PurchaseActivityRepository

    private fun event(order: Long = 1) = OrderConfirmed(
        OrderConfirmed.stableEventId(order),
        order,
        Instant.parse("2026-10-28T00:00:00.123456Z"),
        listOf(PurchasedItem(10, 2), PurchasedItem(20, 3)),
        schemaVersion = 1,
    )
    private fun count(table: String) = jdbc.queryForObject("select count(*) from $table", Long::class.java)!!

    @AfterEach fun clean() {
        reset(repository)
        TransactionTemplate(manager).executeWithoutResult {
            listOf("purchase_activities", "purchase_event_receipts", "purchase_receipts").forEach { jdbc.update("delete from $it") }
        }
    }

    @Test fun `flush clear reload preserves every item and original confirmation time`() {
        val event = event()
        service.record(event)
        TransactionTemplate(manager).executeWithoutResult {
            em.flush()
            em.clear()
            event.items.forEach {
                val stored = em.find(PurchaseActivityJpaEntity::class.java, PurchaseActivityId(event.orderId, it.productId))
                assertThat(stored.quantity).isEqualTo(it.quantity)
                assertThat(stored.confirmedAt).isEqualTo(event.confirmedAt)
            }
        }
        assertThat(count("purchase_receipts")).isEqualTo(1)
        assertThat(count("purchase_event_receipts")).isEqualTo(1)
        assertThat(count("purchase_activities")).isEqualTo(2)
    }

    @Test fun `same record and alternate event IDs have one effect and aliases cannot change orders`() {
        val event = event()
        service.record(event)
        service.record(event)
        val alias = event.copy(eventId = UUID.randomUUID().toString(), items = event.items.reversed())
        service.record(alias)
        assertThat(count("purchase_activities")).isEqualTo(2)
        assertThat(count("purchase_event_receipts")).isEqualTo(2)
        assertThrows<PurchaseEventConflict> { service.record(event.copy(items = listOf(PurchasedItem(10, 99)))) }
        assertThrows<PurchaseEventConflict> { service.record(alias.copy(orderId = 2)) }
        assertThrows<PurchaseEventConflict> { service.record(event.copy(eventId = UUID.randomUUID().toString(), confirmedAt = Instant.EPOCH)) }
        assertThat(count("purchase_receipts")).isEqualTo(1)
        assertThat(count("purchase_activities")).isEqualTo(2)
        assertThat(count("purchase_event_receipts")).isEqualTo(2)
    }

    @Test fun `concurrent same event and alternate identity are deduplicated after unique conflict rollback`() {
        val event = event()
        val inputs = listOf(event, event, event.copy(eventId = UUID.randomUUID().toString()))
        val pool = Executors.newFixedThreadPool(inputs.size)
        val start = CountDownLatch(1)
        try {
            val tasks = inputs.map { input ->
                pool.submit {
                check(start.await(10, TimeUnit.SECONDS))
                service.record(input)
            }
            }
            start.countDown()
            tasks.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            start.countDown()
            pool.shutdownNow()
            check(pool.awaitTermination(10, TimeUnit.SECONDS))
        }
        assertThat(count("purchase_receipts")).isEqualTo(1)
        assertThat(count("purchase_event_receipts")).isEqualTo(2)
        assertThat(count("purchase_activities")).isEqualTo(2)
        val totalQuantity = checkNotNull(jdbc.queryForObject("select sum(quantity) from purchase_activities", Long::class.java))
        assertThat(totalQuantity).isEqualTo(5L)
    }

    @Test fun `failure after all inserts rolls back every item and both receipt types`() {
        val once = AtomicBoolean(true)
        doAnswer {
            it.callRealMethod()
            em.flush()
            if (once.getAndSet(false)) error("stop after actual DB flush but before commit")
            Unit
        }.whenever(repository).insertEvent(any(), any())
        val failure = assertThrows<InvalidDataAccessApiUsageException> { service.record(event()) }
        assertThat(failure.cause).isInstanceOf(IllegalStateException::class.java)
            .hasMessage("stop after actual DB flush but before commit")

        assertThat(count("purchase_receipts")).isZero()
        assertThat(count("purchase_event_receipts")).isZero()
        assertThat(count("purchase_activities")).isZero()
        service.record(event())
        assertThat(count("purchase_activities")).isEqualTo(2)
    }

    @Test fun `MySQL check constraint rejects nonpositive activity quantities`() {
        val failure = assertThrows<UncategorizedSQLException> {
            jdbc.update("insert into purchase_activities(order_id,product_id,quantity,confirmed_at) values(1,10,0,current_timestamp)")
        }
        val sqlFailure = checkNotNull(failure.sqlException)
        assertThat(sqlFailure.errorCode).isEqualTo(3819)
        assertThat(sqlFailure.sqlState).isEqualTo("HY000")

        assertThat(count("purchase_activities")).isZero()
    }

    private fun topic(): String = "week6-${UUID.randomUUID()}".also {
        AdminClient.create(mapOf("bootstrap.servers" to broker.brokersAsString)).use { admin ->
            admin.createTopics(listOf(NewTopic(it, 2, 1))).all().get(10, TimeUnit.SECONDS)
        }
    }

    private fun consumer(group: String) = KafkaConsumer<String, ByteArray>(
        mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to broker.brokersAsString,
            ConsumerConfig.GROUP_ID_CONFIG to group,
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to ByteArrayDeserializer::class.java,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
        ),
    )

    private fun send(topic: String, event: OrderConfirmed): org.apache.kafka.clients.producer.RecordMetadata = KafkaProducer<String, ByteArray>(
        mapOf(
            "bootstrap.servers" to broker.brokersAsString,
            "key.serializer" to StringSerializer::class.java,
            "value.serializer" to ByteArraySerializer::class.java,
            "acks" to "all",
            "enable.idempotence" to true,
        ),
    ).use { it.send(ProducerRecord(topic, event.orderId.toString(), mapper.writeValueAsBytes(event))).get(10, TimeUnit.SECONDS) }

    @Test fun `new consumer in same group replays exact record after DB commit without offset commit`() {
        val topic = topic()
        val group = "redelivery-${UUID.randomUUID()}"
        val event = event()
        val sent = send(topic, event)
        val partition = TopicPartition(topic, sent.partition())
        consumer(group).use { first ->
            first.subscribe(listOf(topic))
            val record = awaitRecord(first)
            assertThat(record.offset()).isEqualTo(sent.offset())
            service.record(mapper.readValue(record.value(), OrderConfirmed::class.java))
            assertThat(first.position(partition)).isEqualTo(sent.offset() + 1)
            assertThat(first.committed(setOf(partition))[partition]).isNull()
            assertThat(count("purchase_activities")).isEqualTo(2)
            println("WEEK6_REDELIVERY first topic=$topic partition=${sent.partition()} offset=${sent.offset()} position=${first.position(partition)} committed=null activities=2")
            // auto commit=false, no pending commits and no close callback: closing does not commit this record.
        }
        consumer(group).use { second ->
            second.subscribe(listOf(topic))
            val replay = awaitRecord(second)
            assertThat(Triple(replay.topic(), replay.partition(), replay.offset())).isEqualTo(Triple(topic, sent.partition(), sent.offset()))
            listener.consume(
                replay,
                org.springframework.kafka.support.Acknowledgment {
                second.commitSync(mapOf(partition to OffsetAndMetadata(replay.offset() + 1)))
            },
            )
            assertThat(count("purchase_activities")).isEqualTo(2)
            assertThat(count("purchase_event_receipts")).isEqualTo(1)
            assertThat(second.committed(setOf(partition))[partition]!!.offset()).isEqualTo(sent.offset() + 1)
            println("WEEK6_REDELIVERY second topic=$topic partition=${replay.partition()} offset=${replay.offset()} committed=${sent.offset() + 1} activities=2")
        }
    }

    private fun awaitRecord(consumer: KafkaConsumer<String, ByteArray>): org.apache.kafka.clients.consumer.ConsumerRecord<String, ByteArray> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25)
        while (System.nanoTime() < deadline) {
            val records = consumer.poll(Duration.ofMillis(200))
            if (!records.isEmpty) return records.first()
        }
        error("Consumer did not receive target record within 25 seconds")
    }

    @Test fun `real listener stops on DB error without committing failed offset then restart succeeds`() {
        val topic = topic()
        val group = "failure-${UUID.randomUUID()}"
        val sent = send(topic, event())
        val partition = TopicPartition(topic, sent.partition())
        val failed = CountDownLatch(1)
        doAnswer {
            failed.countDown()
            error("DB unavailable")
        }.whenever(repository).insertReceipt(any())
        val container = purchaseListenerFactory.createContainer(topic)
        container.containerProperties.groupId = group
        container.containerProperties.setMessageListener(
            AcknowledgingMessageListener<String, ByteArray> { record, ack ->
            listener.consume(record, checkNotNull(ack))
        },
        )
        try {
            container.start()
            check(failed.await(25, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (container.isRunning && System.nanoTime() < deadline) Thread.sleep(50)
            assertThat(container.isRunning).isFalse()
            AdminClient.create(mapOf("bootstrap.servers" to broker.brokersAsString)).use { admin ->
                val committed = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS)
                assertThat(committed[partition]?.offset() ?: sent.offset()).isLessThanOrEqualTo(sent.offset())
                println("WEEK6_FAILURE topic=$topic partition=${sent.partition()} failedOffset=${sent.offset()} committed=${committed[partition]?.offset()} activities=${count("purchase_activities")}")
            }
            assertThat(count("purchase_receipts")).isZero()
            assertThat(count("purchase_activities")).isZero()
            reset(repository)
            container.start()
            val committedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25)
            AdminClient.create(mapOf("bootstrap.servers" to broker.brokersAsString)).use { admin ->
                while (System.nanoTime() < committedDeadline) {
                    val offsets = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS)
                    if (offsets[partition]?.offset() == sent.offset() + 1) break
                    Thread.sleep(100)
                }
                assertThat(admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS)[partition]!!.offset())
                    .isEqualTo(sent.offset() + 1)
            }
            assertThat(count("purchase_activities")).isEqualTo(2)
        } finally {
            container.stop()
        }
    }
}
