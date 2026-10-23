package com.loopers.infrastructure.payment

import com.loopers.domain.payment.PaymentAttemptRepository
import org.springframework.stereotype.Repository

interface PaymentAttemptStorage : PaymentAttemptRepository

@Repository
@org.springframework.context.annotation.Primary
class PaymentAttemptRepositoryImpl(storage: PaymentAttemptStorage) : PaymentAttemptRepository by storage
