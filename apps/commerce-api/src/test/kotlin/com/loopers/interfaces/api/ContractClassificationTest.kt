package com.loopers.interfaces.api

import com.loopers.domain.example.ExampleModel
import com.loopers.infrastructure.example.ExampleJpaRepository
import com.loopers.interfaces.api.example.ExampleV1Dto
import com.loopers.utils.DatabaseCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpEntity
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContractClassificationTest @Autowired constructor(
    private val testRestTemplate: TestRestTemplate,
    private val exampleJpaRepository: ExampleJpaRepository,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    companion object {
        private const val API_V1_URL = "/api/v1"
        private const val EXAMPLES_API_URL = "$API_V1_URL/examples"
    }

    @AfterEach
    fun tearDown() {
        databaseCleanUp.truncateAllTables()
    }

    @DisplayName("저장된 숫자 ID는 성공 응답과 데이터를 반환한다.")
    @Test
    fun returnsSuccess_whenExistingNumericIdIsProvided() {
        // arrange
        val example = exampleJpaRepository.save(ExampleModel(name = "관찰 제목", description = "관찰 설명"))

        // act
        val response = getExample(example.id.toString())

        // assert
        assertAll(
            { assertThat(response.statusCode).isEqualTo(HttpStatus.OK) },
            { assertThat(response.body?.meta?.result).isEqualTo(ApiResponse.Metadata.Result.SUCCESS) },
            { assertThat(response.body?.meta?.errorCode).isNull() },
            { assertThat(response.body?.data).isNotNull() },
        )
    }

    @DisplayName("숫자가 아닌 abc는 BAD_REQUEST 실패 응답을 반환한다.")
    @Test
    fun returnsBadRequest_whenNonNumericIdIsProvided() {
        // arrange
        val id = "abc"

        // act
        val response = getExample(id)

        // assert
        assertFailure(response, HttpStatus.BAD_REQUEST, "Bad Request")
    }

    @DisplayName("존재하지 않는 숫자 ID는 NOT_FOUND 실패 응답을 반환한다.")
    @Test
    fun returnsNotFound_whenMissingNumericIdIsProvided() {
        // arrange
        val id = "-1"

        // act
        val response = getExample(id)

        // assert
        assertFailure(response, HttpStatus.NOT_FOUND, "Not Found")
    }

    @DisplayName("미매핑 URL은 NOT_FOUND 실패 응답을 반환한다.")
    @Test
    fun returnsNotFound_whenUrlIsNotMapped() {
        // arrange
        val unmappedUrl = "$EXAMPLES_API_URL/test/unmapped"

        // act
        val response = get(unmappedUrl)

        // assert
        assertFailure(response, HttpStatus.NOT_FOUND, "Not Found")
    }

    private fun getExample(id: String) = get("$EXAMPLES_API_URL/$id")

    private fun get(url: String) =
        testRestTemplate.exchange(
            url,
            HttpMethod.GET,
            HttpEntity<Any>(Unit),
            object : ParameterizedTypeReference<ApiResponse<ExampleV1Dto.ExampleResponse>>() {},
        )

    private fun assertFailure(
        response: org.springframework.http.ResponseEntity<ApiResponse<ExampleV1Dto.ExampleResponse>>,
        status: HttpStatus,
        errorCode: String,
    ) {
        assertAll(
            { assertThat(response.statusCode).isEqualTo(status) },
            { assertThat(response.body?.meta?.result).isEqualTo(ApiResponse.Metadata.Result.FAIL) },
            { assertThat(response.body?.meta?.errorCode).isEqualTo(errorCode) },
            { assertThat(response.body?.data).isNull() },
        )
    }
}
