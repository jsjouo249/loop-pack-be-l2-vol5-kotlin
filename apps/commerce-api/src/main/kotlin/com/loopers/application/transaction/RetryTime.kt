package com.loopers.application.transaction

import org.springframework.stereotype.Component

@Component
class RetryTime {
    fun nanoTime(): Long = System.nanoTime()

    fun sleep(millis: Long) = Thread.sleep(millis)
}
