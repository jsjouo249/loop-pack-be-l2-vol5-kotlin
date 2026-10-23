package com.loopers.payment

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.loopers.domain.payment.ProviderStatus
import com.loopers.domain.payment.ProviderTransaction
import com.loopers.infrastructure.payment.PgConfiguration
import com.loopers.infrastructure.payment.PgProperties
import com.loopers.infrastructure.payment.TestPgAdapter
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PgHttpFixture(responseTimeoutMillis: Long = 500, clock: Clock = Clock.systemUTC()) : AutoCloseable {
    val mapper = jacksonObjectMapper()
    val transactions = ConcurrentHashMap<String, ProviderTransaction>()
    val creates = AtomicInteger()
    val lookups = AtomicInteger()
    val errors = CopyOnWriteArrayList<Throwable>()
    val requests = CopyOnWriteArrayList<CapturedRequest>()

    @Volatile var createMode = "PENDING"

    @Volatile var getCode = 200

    @Volatile var rejectionResponse: String? = null

    @Volatile var createResponse: String? = null

    @Volatile var afterCreate: (() -> Unit)? = null

    @Volatile var beforeLookup: (() -> Unit)? = null
    private val executor = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        this.executor = this@PgHttpFixture.executor
        createContext("/api/v1/payments") { handle(it) }
        start()
    }
    val properties = PgProperties("http://127.0.0.1:${server.address.port}")
    private val factory = PgConfiguration().pgRequestFactory().also {
        it.setReadTimeout(java.time.Duration.ofMillis(responseTimeoutMillis))
    }
    val circuit = PgConfiguration.circuit(clock)
    val adapter = TestPgAdapter(PgConfiguration().pgRestClient(properties, factory), mapper, properties, circuit)

    fun complete(status: ProviderStatus) {
        transactions.replaceAll { _, t -> t.copy(status = status) }
    }
    fun duplicate() {
        val t = transactions.values.first()
        transactions["DUPLICATE"] = t.copy(transactionKey = "DUPLICATE")
    }
    private fun handle(exchange: HttpExchange) {
        try {
            val body = exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8)
            requests.add(
                CapturedRequest(
                    exchange.requestMethod,
                    exchange.requestURI.toString(),
                    exchange.requestHeaders.getFirst("X-USER-ID"),
                    body,
                ),
            )
            if (exchange.requestMethod == "POST") {
                creates.incrementAndGet()
                val input = mapper.readTree(body)
                if (createMode == "REJECT") {
                    respond(
                        exchange,
                        422,
                        rejectionResponse ?: mapper.writeValueAsString(
                            mapOf(
                            "code" to "TEST_PG_REJECTED_BEFORE_CREATE", "orderId" to input["orderId"].asText(),
                            "amount" to input["amount"].longValue(), "transactionCreated" to false,
                        ),
                        ),
                    )
                    return
                }
                if (createMode == "BAD_422") {
                    respond(exchange, 422, "{}")
                    return
                }
                val t = ProviderTransaction(
                    "TX-${creates.get()}",
                    input["orderId"].asText(),
                    input["amount"].longValue(),
                    if (createMode == "SUCCESS") ProviderStatus.SUCCESS else ProviderStatus.PENDING,
                )
                transactions[t.transactionKey] = t
                afterCreate?.invoke()
                if (createMode == "LOST") {
                    exchange.close()
                    return
                }
                respond(exchange, 202, createResponse ?: mapper.writeValueAsString(t))
            } else {
                lookups.incrementAndGet()
                beforeLookup?.invoke()
                if (getCode != 200) {
                    respond(exchange, getCode, "{}")
                    return
                }
                val query = exchange.requestURI.rawQuery
                if (query != null) {
                    val order = URLDecoder.decode(query.substringAfter("orderId="), StandardCharsets.UTF_8)
                    respond(exchange, 200, mapper.writeValueAsString(transactions.values.filter { it.orderId == order }))
                } else {
                    val key = URLDecoder.decode(exchange.requestURI.rawPath.substringAfterLast('/'), StandardCharsets.UTF_8)
                    val t = transactions[key]
                    respond(exchange, if (t == null) 404 else 200, mapper.writeValueAsString(t))
                }
            }
        } catch (failure: Throwable) {
            if (failure !is java.io.IOException) errors.add(failure)
            exchange.close()
        }
    }
    private fun respond(exchange: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }
    override fun close() {
        server.stop(0)
        factory.destroy()
        executor.shutdownNow()
        check(executor.awaitTermination(5, TimeUnit.SECONDS))
    }
}
data class CapturedRequest(val method: String, val uri: String, val account: String?, val body: String)
