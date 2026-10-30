package com.loopers.kafka

import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringSerializer
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KafkaAssignmentExperimentTest {
    private val topic = "assignment-${UUID.randomUUID()}"
    private val broker = EmbeddedKafkaKraftBroker(1, 2, topic)

    @BeforeAll fun start() = broker.afterPropertiesSet()

    @AfterAll fun stop() = broker.destroy()

    private fun consumer(group: String) = KafkaConsumer<String, ByteArray>(
        mapOf(
            "bootstrap.servers" to broker.brokersAsString,
            "group.id" to group,
            "key.deserializer" to StringDeserializer::class.java,
            "value.deserializer" to ByteArrayDeserializer::class.java,
            "enable.auto.commit" to false,
            "auto.offset.reset" to "earliest",
        ),
    ).apply { subscribe(listOf(topic)) }

    private fun awaitAssignments(consumers: List<KafkaConsumer<String, ByteArray>>, sizes: List<Int>) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25)
        var stable = 0
        while (System.nanoTime() < deadline) {
            consumers.forEach { it.poll(Duration.ofMillis(100)) }
            val assignments = consumers.map { it.assignment() }
            if (assignments.map { it.size }.sorted() == sizes.sorted() &&
                assignments.flatten().map { it.partition() }.sorted() == listOf(0, 1)
            ) {
                stable++
                if (stable == 3) {
                    println("WEEK6_ASSIGNMENT members=${consumers.size} partitions=2 assignments=$assignments")
                    return
                }
            } else {
                stable = 0
            }
        }
        error("Assignment did not stabilize: ${consumers.map { it.assignment() }} expected=$sizes")
    }

    @Test
    @Timeout(120)
    fun `two partitions are shared by one two three members and independently assigned to another group`() {
        val group = "shared-${UUID.randomUUID()}"
        val consumers = mutableListOf<KafkaConsumer<String, ByteArray>>()
        try {
            consumers.add(consumer(group))
            awaitAssignments(consumers, listOf(2))
            consumers.add(consumer(group))
            awaitAssignments(consumers, listOf(1, 1))
            consumers.add(consumer(group))
            awaitAssignments(consumers, listOf(0, 1, 1))
            consumer("independent-${UUID.randomUUID()}").use { independent ->
                awaitAssignments(listOf(independent), listOf(2))
                awaitAssignments(consumers, listOf(0, 1, 1))
                assertThat(independent.assignment().map { it.partition() }).containsExactlyInAnyOrder(0, 1)
                println("WEEK6_INDEPENDENT_GROUP allPartitions=${independent.assignment()}")
            }
        } finally {
            consumers.reversed().forEach { it.close(Duration.ofSeconds(5)) }
        }
    }

    @Test
    @Timeout(60)
    fun `closing without commit lets a new consumer in the same group read the exact record`() {
        val sent = KafkaProducer<String, String>(
            mapOf(
                "bootstrap.servers" to broker.brokersAsString,
                "key.serializer" to StringSerializer::class.java,
                "value.serializer" to StringSerializer::class.java,
                "acks" to "all",
                "enable.idempotence" to true,
            ),
        ).use { it.send(ProducerRecord(topic, "42", "transport-redelivery")).get(10, TimeUnit.SECONDS) }
        val partition = TopicPartition(topic, sent.partition())
        val group = "replay-${UUID.randomUUID()}"
        consumer(group).use { first ->
            val record = awaitRecord(first)
            assertThat(record.offset()).isEqualTo(sent.offset())
            assertThat(first.position(partition)).isEqualTo(sent.offset() + 1)
            assertThat(first.committed(setOf(partition))[partition]).isNull()
            println("WEEK6_OFFSET_REDELIVERY first topic=$topic partition=${sent.partition()} offset=${sent.offset()} position=${first.position(partition)} committed=null")
        }
        consumer(group).use { second ->
            val record = awaitRecord(second)
            assertThat(Triple(record.topic(), record.partition(), record.offset())).isEqualTo(Triple(topic, sent.partition(), sent.offset()))
            assertThat(String(record.value(), Charsets.UTF_8)).isEqualTo("transport-redelivery")
            second.commitSync(mapOf(partition to OffsetAndMetadata(record.offset() + 1)))
            assertThat(second.committed(setOf(partition))[partition]!!.offset()).isEqualTo(sent.offset() + 1)
            println("WEEK6_OFFSET_REDELIVERY second topic=$topic partition=${record.partition()} offset=${record.offset()} committed=${sent.offset() + 1}")
        }
    }

    private fun awaitRecord(consumer: KafkaConsumer<String, ByteArray>): org.apache.kafka.clients.consumer.ConsumerRecord<String, ByteArray> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline) {
            val records = consumer.poll(Duration.ofMillis(200))
            if (!records.isEmpty) return records.first()
        }
        error("Record was not received within 20 seconds")
    }
}
