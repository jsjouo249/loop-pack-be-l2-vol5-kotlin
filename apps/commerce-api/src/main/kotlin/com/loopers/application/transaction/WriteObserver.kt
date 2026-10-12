package com.loopers.application.transaction

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class WriteObserver {
    private val log = LoggerFactory.getLogger(javaClass)

    fun attempt(requestId: String, operation: String, number: Int, outcome: String) {
        log.info("write_attempt request={} operation={} attempt={} outcome={}", requestId, operation, number, outcome)
    }

    fun request(requestId: String, operation: String, attempts: Int, outcome: String) {
        log.info("write_request request={} operation={} attempts={} outcome={}", requestId, operation, attempts, outcome)
    }
}
