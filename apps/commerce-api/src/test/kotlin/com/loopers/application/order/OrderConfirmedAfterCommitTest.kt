package com.loopers.application.order

import com.loopers.domain.order.event.OrderConfirmed
import com.loopers.domain.order.event.OrderConfirmedPublisher
import com.loopers.domain.order.event.PurchasedItem
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

class OrderConfirmedAfterCommitTest {
    private val event = OrderConfirmed(OrderConfirmed.stableEventId(1), 1, Instant.EPOCH, listOf(PurchasedItem(2, 3)), schemaVersion = 1)

    @Test fun `Spring dispatches only after commit and never on rollback or absent transaction`() {
        AnnotationConfigApplicationContext(EventConfiguration::class.java).use { context ->
            val publisher = context.getBean(OrderConfirmedPublisher::class.java)
            val manager = context.getBean(RecordingTransactionManager::class.java)
            doAnswer {
                assertThat(manager.commits).isEqualTo(1)
                Unit
            }.whenever(publisher).publish(any())
            context.publishEvent(event)
            assertThrows<IllegalStateException> {
                TransactionTemplate(manager).executeWithoutResult {
                    context.publishEvent(event)
                    error("rollback")
                }
            }
            verifyNoInteractions(publisher)
            TransactionTemplate(manager).executeWithoutResult {
                context.publishEvent(event)
                verifyNoInteractions(publisher)
            }
            verify(publisher).publish(event)
            assertThat(manager.commits).isEqualTo(1)
        }
    }

    @Test fun `publication exception cannot turn committed transaction into rollback`() {
        AnnotationConfigApplicationContext(EventConfiguration::class.java).use { context ->
            val manager = context.getBean(RecordingTransactionManager::class.java)
            val publisher = context.getBean(OrderConfirmedPublisher::class.java)
            doThrow(IllegalStateException("broker failure")).whenever(publisher).publish(any())
            TransactionTemplate(manager).executeWithoutResult { context.publishEvent(event) }
            assertThat(manager.commits).isEqualTo(1)
            assertThat(manager.rollbacks).isZero()
        }
    }

    @TestConfiguration
    @EnableTransactionManagement
    class EventConfiguration {
        @Bean fun publisher(): OrderConfirmedPublisher = mock()

        @Bean fun manager() = RecordingTransactionManager()

        @Bean fun listener(publisher: OrderConfirmedPublisher) = OrderConfirmedAfterCommit(publisher)
    }

    class RecordingTransactionManager : AbstractPlatformTransactionManager() {
        var commits = 0
        var rollbacks = 0
        override fun doGetTransaction(): Any = Any()
        override fun doBegin(transaction: Any, definition: TransactionDefinition) = Unit
        override fun doCommit(status: DefaultTransactionStatus) {
            commits++
        }
        override fun doRollback(status: DefaultTransactionStatus) {
            rollbacks++
        }
    }
}
