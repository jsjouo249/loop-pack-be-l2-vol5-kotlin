package com.loopers.application.transaction

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderConcurrencyTest : CommerceTransactionTestSupport() {
    @Test
    fun `eight orders sell exactly five units`() {
        val product = product(1000, 5)
        val inputs = (1..8).map { buyer(10_000).let { it to draft(it, product) } }
        observer.events.clear()
        val outcomes = race(
            inputs.map { (user, order) ->
                "$order" to {
            orders.confirm(user, order)
            Unit
        }
            },
        )
        assertOutcomes(outcomes, "SUCCESS" to 5, "INSUFFICIENT_STOCK" to 3)
        assertThat(stock(product)).isZero()
        assertThat(number("select count(*) from orders where status = 'CONFIRMED'")).isEqualTo(5)
        outcomes.forEach { outcome ->
            val status = jdbc.queryForObject("select status from orders where id = ?", String::class.java, outcome.id.toLong())
            assertThat(status).isEqualTo(if (outcome.outcome == "SUCCESS") "CONFIRMED" else "DRAFT")
        }
        assertThat(number("select sum(payment_amount) from orders where status = 'CONFIRMED'")).isEqualTo(5000)
        assertThat(number("select sum(balance) from point_balances")).isEqualTo(75_000)
        println("FINAL_DB stock=${stock(product)} confirmed=5 draft=3 totalBalance=75000")
    }

    @Test
    fun `three four thousand orders spend eight thousand exactly`() {
        val buyer = buyer(10_000)
        val product = product(4000, 10)
        val drafts = (1..3).map { draft(buyer, product) }
        observer.events.clear()
        assertOutcomes(
            race(
                drafts.map {
                    "$it" to {
            orders.confirm(buyer, it)
            Unit
        }
                },
            ),
            "SUCCESS" to 2,
            "INSUFFICIENT_POINTS" to 1,
        )
        assertThat(balance(buyer)).isEqualTo(2000)
        assertThat(stock(product)).isEqualTo(8)
        assertThat(number("select sum(payment_amount) from orders where status = 'CONFIRMED'")).isEqualTo(8000)
        println("FINAL_DB balance=${balance(buyer)} stock=${stock(product)} confirmed=2 draft=1")
    }

    @Test
    fun `charge and payment both survive`() {
        val buyer = buyer(10_000)
        val product = product(7000, 1)
        val order = draft(buyer, product)
        observer.events.clear()
        assertOutcomes(
            race(
                listOf(
                    "charge" to {
            points.charge(buyer, 2000)
            Unit
        },
            "$order" to {
            orders.confirm(buyer, order)
            Unit
        },
                ),
            ),
            "SUCCESS" to 2,
        )
        assertThat(balance(buyer)).isEqualTo(5000)
        assertThat(stock(product)).isZero()
        println("FINAL_DB balance=${balance(buyer)} stock=${stock(product)}")
    }

    @Test
    fun `same order is charged once`() {
        val buyer = buyer(10_000)
        val product = product(2000, 5)
        val order = draft(buyer, product)
        observer.events.clear()
        assertOutcomes(
            race(
                (1..4).map {
                    "$order-request-$it" to {
            orders.confirm(buyer, order)
            Unit
        }
                },
            ),
            "SUCCESS" to 1,
            "ORDER_ALREADY_CONFIRMED" to 3,
        )
        assertThat(balance(buyer)).isEqualTo(8000)
        assertThat(stock(product)).isEqualTo(4)
        assertThat(number("select count(*) from orders where status = 'CONFIRMED'")).isEqualTo(1)
    }

    @Test
    fun `concurrent charges sum instead of overwriting`() {
        val buyer = buyer(0)
        observer.events.clear()
        assertOutcomes(
            race(
                (1..5).map {
                    "charge-$it" to {
            points.charge(buyer, 2000)
            Unit
        }
                },
            ),
            "SUCCESS" to 5,
        )
        assertThat(balance(buyer)).isEqualTo(10_000)
        assertThat(number("select count(*) from point_balances where user_id = ?", buyer)).isEqualTo(1)
    }
}
