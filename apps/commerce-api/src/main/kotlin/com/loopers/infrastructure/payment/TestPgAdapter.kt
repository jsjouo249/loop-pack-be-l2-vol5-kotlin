package com.loopers.infrastructure.payment

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.loopers.domain.payment.ObservationKind
import com.loopers.domain.payment.PaymentObservation
import com.loopers.domain.payment.PaymentProvider
import com.loopers.domain.payment.ProviderStatus
import com.loopers.domain.payment.ProviderTransaction
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import java.time.Duration

/** Protocol observations only: order transitions and resource effects belong to the domain/application boundary. */
@Component
class TestPgAdapter(
    @Qualifier("pgRestClient") private val client: RestClient,
    private val mapper: ObjectMapper,
    private val properties: PgProperties,
    private val circuit: CircuitBreaker,
) : PaymentProvider {
    override fun create(providerOrderId: String, amount: Long): PaymentObservation = try {
        client.post().uri("/api/v1/payments").body(
            mapOf(
            "orderId" to providerOrderId,
                "amount" to amount,
                "cardType" to "TEST",
                "cardNo" to "0000-0000-0000-0000",
            "callbackUrl" to properties.callbackUrl,
        ),
        ).exchange { _, response ->
            val body = mapper.readTree(response.body)
            if (response.statusCode.value() == 422 && body?.get("code")?.textValue() == "TEST_PG_REJECTED_BEFORE_CREATE" &&
                body.get("transactionCreated")?.isBoolean == true && !body.get("transactionCreated").booleanValue() &&
                body.get("orderId")?.textValue() == providerOrderId && integer(body.get("amount")) == amount
            ) {
                PaymentObservation(ObservationKind.REJECTED_NO_EFFECT)
            } else if (response.statusCode.value() == 202) {
                observation(transaction(body))
            } else {
                PaymentObservation(ObservationKind.UNKNOWN)
            }
        }
    } catch (_: Exception) {
        // Never include a request/response body (or a synthetic card number) in an exception/log.
        PaymentObservation(ObservationKind.UNKNOWN)
    }

    override fun lookup(providerOrderId: String, expectedTransactionKey: String?, callback: Boolean): PaymentObservation {
        val deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos()
        return try {
            val callbackTransaction = if (callback && expectedTransactionKey != null) {
                val node = get(deadline) { client.get().uri("/api/v1/payments/{key}", expectedTransactionKey) }
                if (node == null) return PaymentObservation(ObservationKind.NOT_FOUND)
                transaction(node)
            } else {
                null
            }
            val list = get(deadline) {
                client.get().uri { it.path("/api/v1/payments").queryParam("orderId", "{orderId}").build(providerOrderId) }
            }
                ?: return PaymentObservation(ObservationKind.NOT_FOUND)
            if (!list.isArray) throw LookupFailure(false)
            if (list.size() == 0) return PaymentObservation(ObservationKind.NOT_FOUND)
            if (list.size() != 1) {
                return PaymentObservation(
                    ObservationKind.CONFLICT,
                    issue = "MULTIPLE_TRANSACTIONS",
                    quarantine = true,
                )
            }
            val listed = transaction(list[0])
            if (expectedTransactionKey != null && listed.transactionKey != expectedTransactionKey) {
                return PaymentObservation(ObservationKind.CONFLICT, listed, "TRANSACTION_MISMATCH")
            }
            val direct = callbackTransaction ?: run {
                val node = get(deadline) { client.get().uri("/api/v1/payments/{key}", listed.transactionKey) }
                    ?: return PaymentObservation(ObservationKind.NOT_FOUND)
                transaction(node)
            }
            if (listed.transactionKey != direct.transactionKey || listed.orderId != direct.orderId ||
                listed.amount != direct.amount
            ) {
                PaymentObservation(ObservationKind.CONFLICT, direct, "IDENTITY_MISMATCH")
            } else if (listed.status != ProviderStatus.PENDING && direct.status != ProviderStatus.PENDING &&
                listed.status != direct.status
            ) {
                PaymentObservation(ObservationKind.CONFLICT, direct, "TERMINAL_CONFLICT")
            } else {
                observation(
                    if (direct.status == ProviderStatus.PENDING && listed.status != ProviderStatus.PENDING) listed else direct,
                )
            }
        } catch (_: CallNotPermittedException) {
            PaymentObservation(ObservationKind.UNAVAILABLE, issue = "CIRCUIT_OPEN")
        } catch (_: Exception) {
            PaymentObservation(ObservationKind.UNAVAILABLE, issue = "LOOKUP_UNAVAILABLE")
        }
    }

    private fun get(deadline: Long, request: () -> RestClient.RequestHeadersSpec<*>): JsonNode? {
        repeat(2) { attempt ->
            if (deadline - System.nanoTime() < Duration.ofMillis(800).toNanos()) throw LookupFailure(false)
            try {
                return circuit.executeSupplier {
                    try {
                        request().exchange { _, response ->
                            val code = response.statusCode.value()
                            if (code == 404) {
                                null
                            } else {
                                if (code != 200) throw LookupFailure(code in setOf(502, 503, 504))
                                try {
                                    mapper.readTree(response.body) ?: throw LookupFailure(false)
                                } catch (
                                    _: Exception,
                                ) {
                                    throw LookupFailure(false)
                                }
                            }
                        }
                    } catch (_: ResourceAccessException) {
                        throw LookupFailure(true)
                    }
                }
            } catch (failure: LookupFailure) {
                if (!failure.retryable || attempt == 1 || circuit.state == CircuitBreaker.State.OPEN ||
                    deadline - System.nanoTime() < Duration.ofMillis(900).toNanos()
                ) {
                        throw failure
                    }
                try {
                    Thread.sleep(100)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw LookupFailure(false)
                }
            }
        }
        throw LookupFailure(false)
    }

    private fun transaction(node: JsonNode?): ProviderTransaction {
        if (node == null || !node.isObject) throw LookupFailure(false)
        val key = node.get("transactionKey")?.takeIf { it.isTextual }?.textValue()?.takeIf { it.isNotBlank() && it.length <= 128 }
            ?: throw LookupFailure(false)
        val order = node.get("orderId")?.takeIf { it.isTextual }?.textValue()?.takeIf { it.length in 6..128 }
            ?: throw LookupFailure(false)
        val amount = integer(node.get("amount"))?.takeIf { it >= 0 } ?: throw LookupFailure(false)
        val status = ProviderStatus.valueOf(node.get("status")?.textValue() ?: throw LookupFailure(false))
        return ProviderTransaction(key, order, amount, status)
    }
    private fun integer(node: JsonNode?): Long? = node?.takeIf { it.isIntegralNumber && it.canConvertToLong() }?.longValue()
    private fun observation(t: ProviderTransaction) = PaymentObservation(ObservationKind.valueOf(t.status.name), t)
}

class LookupFailure(val retryable: Boolean) : RuntimeException("PG lookup unavailable")
