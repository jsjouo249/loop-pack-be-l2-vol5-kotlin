package com.loopers.application.example

import com.loopers.domain.example.ExampleException
import com.loopers.domain.example.ExampleFailure
import com.loopers.domain.example.ExampleRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class ExampleFacade(
    private val examples: ExampleRepository,
) {
    @Transactional(readOnly = true)
    fun getExample(id: Long): ExampleInfo {
        val example = examples.find(id)
            ?: throw ExampleException(ExampleFailure.NOT_FOUND, "[id = $id] 예시를 찾을 수 없습니다.")
        return ExampleInfo.from(example)
    }
}
