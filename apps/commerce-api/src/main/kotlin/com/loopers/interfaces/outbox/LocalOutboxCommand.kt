package com.loopers.interfaces.outbox

import com.loopers.application.outbox.OutboxRecovery
import com.loopers.application.outbox.OutboxRelay
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.stereotype.Component

@Component
@Profile("outbox-local")
@ConditionalOnProperty(name = ["outbox.command.enabled"], havingValue = "true")
class LocalOutboxCommand(
    private val recovery: OutboxRecovery,
    private val relay: OutboxRelay,
    private val context: ConfigurableApplicationContext,
    @Value("\${outbox.relay.enabled:false}") private val scheduledRelayEnabled: Boolean,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        require(!scheduledRelayEnabled) { "Disable the scheduled relay for the local command" }
        val orderIds = args.getOptionValues("restore-order-id").orEmpty()
        val eventIds = args.getOptionValues("relay-event-id").orEmpty()
        require(orderIds.isNotEmpty() || eventIds.isNotEmpty()) { "Specify restore-order-id or relay-event-id" }
        orderIds.forEach { recovery.restore(it.toLong()) }
        eventIds.forEach { relay.runOnce(it) }
        context.close()
    }
}
