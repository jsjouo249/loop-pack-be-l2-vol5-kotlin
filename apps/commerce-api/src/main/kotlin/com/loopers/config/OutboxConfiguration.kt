package com.loopers.config

import com.loopers.application.outbox.OutboxRelay
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock

@Configuration
class OutboxConfiguration {
    @Bean
    fun outboxClock(): Clock = Clock.systemUTC()
}

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = ["outbox.relay.enabled"], havingValue = "true")
class OutboxSchedulingConfiguration

@Component
@ConditionalOnProperty(name = ["outbox.relay.enabled"], havingValue = "true")
class OutboxScheduler(private val relay: OutboxRelay) {
    @Scheduled(fixedDelayString = "\${outbox.relay.poll-delay-ms:1000}")
    fun tick() {
        relay.runOnce()
    }
}
