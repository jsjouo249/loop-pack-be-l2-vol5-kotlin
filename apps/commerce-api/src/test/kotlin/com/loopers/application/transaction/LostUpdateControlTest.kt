package com.loopers.application.transaction

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit

class LostUpdateControlTest : CommerceTransactionTestSupport() {
    @Test
    fun `two successful constant writes violate the stock conservation invariant`() {
        val product = product(1000, 5)
        val afterRead = CyclicBarrier(2)
        try {
            val outcomes = race(
                (1..2).map { worker ->
                "control-$worker" to {
                    tx {
                        assertThat(stock(product)).isEqualTo(5)
                        afterRead.await(10, TimeUnit.SECONDS)
                        // 갱신 유실 대조군: 두 트랜잭션이 모두 재고 5를 읽은 뒤 상수 4를 저장한다.
                        jdbc.update("update products set stock = 4 where id = ?", product)
                    }
                    Unit
                }
            },
            )
            assertOutcomes(outcomes, "SUCCESS" to 2)
            assertThat(stock(product)).isEqualTo(4)
            assertThat(2 + stock(product)).isNotEqualTo(5)
            println("FINAL_DB controlSuccess=2 stock=${stock(product)} invariant=VIOLATED")
        } finally {
            afterRead.reset()
        }
    }
}
