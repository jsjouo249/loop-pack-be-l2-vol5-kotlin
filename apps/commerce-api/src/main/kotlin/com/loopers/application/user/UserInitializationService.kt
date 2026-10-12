package com.loopers.application.user

import com.loopers.domain.point.PointAccount
import com.loopers.domain.point.PointAccountRepository
import com.loopers.domain.user.User
import com.loopers.domain.user.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class UserInitializationService(private val users: UserRepository, private val accounts: PointAccountRepository) {
    // 별도 Aggregate인 사용자와 0원 계정을 함께 생성하며, 어느 한쪽이 실패해도 모두 롤백한다.
    @Transactional
    fun create(name: String): User {
        val user = users.save(User(name))
        accounts.create(PointAccount.open(user.id))
        return user
    }
}
