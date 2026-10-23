package com.loopers.domain.payment

import com.loopers.domain.inventory.StockReservation
import com.loopers.domain.inventory.ReservationStatus
import com.loopers.domain.commerce.CommerceException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PaymentAttemptTest {
    private fun attempt() = PaymentAttempt(42, 1, "attempt-42", 7000, "LP-ORD-0000000042")
    private fun observation(status: ProviderStatus) = PaymentObservation(
        ObservationKind.valueOf(status.name),
        ProviderTransaction("TX-42", "LP-ORD-0000000042", 7000, status),
    )

    @Test
    fun `terminal observation is applied once and never downgraded by late handlers`() {
        val attempt = attempt()
        assertThat(attempt.observe(observation(ProviderStatus.PENDING))).isEqualTo(ResultEffect.NONE)
        assertThat(attempt.status).isEqualTo(AttemptStatus.PENDING)
        assertThat(attempt.observe(observation(ProviderStatus.SUCCESS))).isEqualTo(ResultEffect.SUCCESS)
        assertThat(attempt.observe(observation(ProviderStatus.SUCCESS))).isEqualTo(ResultEffect.NONE)
        attempt.observe(PaymentObservation(ObservationKind.UNKNOWN))
        attempt.observe(observation(ProviderStatus.PENDING))
        attempt.observe(observation(ProviderStatus.FAILED))
        assertThat(attempt.status).isEqualTo(AttemptStatus.SUCCESS)
        assertThat(attempt.lastCheck).isEqualTo("TERMINAL_CONFLICT")
    }

    @Test
    fun `empty lookup and unavailable never release or change known pending into failure`() {
        val attempt = attempt()
        attempt.observe(observation(ProviderStatus.PENDING))
        attempt.observe(PaymentObservation(ObservationKind.NOT_FOUND))
        attempt.observe(PaymentObservation(ObservationKind.UNAVAILABLE))
        assertThat(attempt.status).isEqualTo(AttemptStatus.PENDING)
    }

    @Test
    fun `rejection has no transaction key and repeated rejection has no extra effect`() {
        val attempt = attempt()
        val rejection = PaymentObservation(ObservationKind.REJECTED_NO_EFFECT)
        assertThat(attempt.observe(rejection)).isEqualTo(ResultEffect.FAILED)
        assertThat(attempt.observe(rejection)).isEqualTo(ResultEffect.NONE)
        assertThat(attempt.transactionKey).isNull()
    }

    @Test
    fun `mismatches cannot bind transaction and multiple transactions remain quarantined`() {
        val attempt = attempt()
        assertThat(attempt.observe(observation(ProviderStatus.SUCCESS).copy(account = "OTHER"))).isEqualTo(ResultEffect.NONE)
        assertThat(attempt.transactionKey).isNull()
        val mismatch = observation(ProviderStatus.SUCCESS).copy(transaction = ProviderTransaction("OTHER", "LP-ORD-0000000042", 7001, ProviderStatus.SUCCESS))
        assertThat(attempt.observe(mismatch)).isEqualTo(ResultEffect.NONE)
        assertThat(attempt.transactionKey).isNull()
        attempt.observe(PaymentObservation(ObservationKind.CONFLICT, issue = "MULTIPLE_TRANSACTIONS", quarantine = true))
        assertThat(attempt.observe(observation(ProviderStatus.SUCCESS))).isEqualTo(ResultEffect.NONE)
        assertThat(attempt.quarantined).isTrue()
        assertThat(attempt.status).isEqualTo(AttemptStatus.UNKNOWN)
    }

    @Test
    fun `request key and amount must match and reservation terminates once`() {
        val attempt = attempt()
        assertThrows<CommerceException> { attempt.assertRequest("different", 7000) }
        assertThrows<CommerceException> { attempt.assertRequest(attempt.key, 7001) }
        val reservation = StockReservation(42, 2, 2)
        reservation.release()
        assertThat(reservation.status).isEqualTo(ReservationStatus.RELEASED)
        assertThrows<IllegalStateException> { reservation.release() }
        assertThrows<IllegalStateException> { reservation.consume() }
    }
}
