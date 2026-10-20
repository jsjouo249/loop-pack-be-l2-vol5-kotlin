package com.loopers

import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.ScanOptions
import org.springframework.test.context.TestContext
import org.springframework.test.context.support.AbstractTestExecutionListener

class ProductListCacheTestIsolation : AbstractTestExecutionListener() {
    override fun beforeTestMethod(testContext: TestContext) {
        if (!testContext.applicationContext.containsBean("masterRedisTemplate")) return
        @Suppress("UNCHECKED_CAST")
        val redis = testContext.applicationContext.getBean("masterRedisTemplate") as RedisTemplate<String, String>
        redis.scan(ScanOptions.scanOptions().match("products:v1:list:*").count(200).build()).use { cursor ->
            val keys = cursor.asSequence().toList()
            if (keys.isNotEmpty()) redis.delete(keys)
        }
    }
}
