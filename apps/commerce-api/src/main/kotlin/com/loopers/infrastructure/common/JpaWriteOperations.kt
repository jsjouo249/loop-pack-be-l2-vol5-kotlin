package com.loopers.infrastructure.common

import jakarta.persistence.EntityManager
import jakarta.persistence.FlushModeType
import jakarta.persistence.LockModeType
import org.hibernate.engine.spi.SessionImplementor
import org.springframework.stereotype.Component

@Component
class JpaWriteOperations(private val em: EntityManager) {
    fun flush() = em.flush()

    fun update(sql: String, vararg parameters: Pair<String, Any>): Int {
        // 호출부가 필요한 변경을 먼저 flush하므로 SQL마다 전체 영속성 컨텍스트를 자동 flush하지 않는다.
        val query = em.createNativeQuery(sql).setFlushMode(FlushModeType.COMMIT)
        parameters.forEach { (name, value) -> query.setParameter(name, value) }
        return query.executeUpdate()
    }

    // UPDATE가 이미 해당 행의 쓰기 잠금을 보유한 상태에서 현재값을 읽는다.
    // 동일 값 UPDATE도 REPEATABLE READ의 과거 스냅샷이나 관리 중인 객체의 낡은 값을 반환하지 않게 한다.
    fun <T> current(type: Class<T>, id: Long): T {
        val entity = checkNotNull(em.find(type, id))
        em.refresh(entity, LockModeType.PESSIMISTIC_WRITE)
        return entity
    }

    fun <T> lock(type: Class<T>, id: Long): T? {
        // final JPA 타입은 getReference도 SELECT한다. Hibernate context의 PK lookup은 SQL 없이 O(1)이다.
        val session = em.unwrap(SessionImplementor::class.java)
        val persister = session.factory.mappingMetamodel.getEntityDescriptor(type)
        val managed: Any? = session.persistenceContextInternal.getEntity(session.generateEntityKey(id, persister))
        return if (managed != null) {
            em.refresh(managed, LockModeType.PESSIMISTIC_WRITE)
            type.cast(managed)
        } else {
            em.find(type, id, LockModeType.PESSIMISTIC_WRITE)
        }
    }
}
