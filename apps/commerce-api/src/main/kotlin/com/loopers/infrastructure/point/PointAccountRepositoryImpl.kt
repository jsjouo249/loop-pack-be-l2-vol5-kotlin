package com.loopers.infrastructure.point

import com.loopers.domain.point.PointAccount
import com.loopers.domain.point.PointAccountRepository
import org.springframework.stereotype.Repository

@Repository
class PointAccountRepositoryImpl(private val repository: PointAccountEntityRepository) : PointAccountRepository {
    override fun findByUserId(userId: Long): PointAccount? =
        repository.findByUserId(userId)?.toDomain()

    override fun create(account: PointAccount): PointAccount =
        repository.create(PointAccountEntity(account.userId, account.balance.amount)).toDomain()

    override fun findByUserIdForUpdate(userId: Long): PointAccount? = repository.findByUserIdForUpdate(userId)?.toDomain()

    override fun saveBalance(account: PointAccount): PointAccount =
        repository.saveBalance(PointAccountEntity(account.userId, account.balance.amount)).toDomain()

    private fun PointAccountEntity.toDomain(): PointAccount = PointAccount.reconstitute(userId, balance)
}
