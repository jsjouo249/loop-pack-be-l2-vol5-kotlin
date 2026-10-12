package com.loopers.interfaces.api

import com.loopers.application.transaction.TransactionBusyException
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.exc.InvalidFormatException
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.loopers.domain.commerce.CommerceException
import com.loopers.domain.commerce.CommerceFailure
import com.loopers.domain.product.InsufficientStockException
import com.loopers.domain.example.ExampleException
import com.loopers.domain.example.ExampleFailure
import jakarta.servlet.http.HttpServletRequest
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.http.HttpStatus
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.server.ServerWebInputException
import org.springframework.web.servlet.resource.NoResourceFoundException
import kotlin.collections.joinToString
import kotlin.jvm.java
import kotlin.text.isNotEmpty
import kotlin.text.toRegex

@RestControllerAdvice
class ApiControllerAdvice {
    private val log = LoggerFactory.getLogger(ApiControllerAdvice::class.java)

    @ExceptionHandler
    fun handle(e: ExampleException): ResponseEntity<ApiResponse<*>> = failureResponse(
        when (e.reason) {
            ExampleFailure.INVALID_INPUT -> ErrorType.BAD_REQUEST
            ExampleFailure.NOT_FOUND -> ErrorType.NOT_FOUND
        },
        e.message,
    )

    @ExceptionHandler
    fun handle(e: TransactionBusyException): ResponseEntity<ApiResponse<*>> = ResponseEntity(
        ApiResponse.fail("TRANSACTION_BUSY", "다른 요청을 처리 중입니다. 잠시 후 상태를 확인해 주세요."),
        HttpStatus.SERVICE_UNAVAILABLE,
    )

    @ExceptionHandler
    fun handle(e: CommerceException): ResponseEntity<ApiResponse<*>> {
        val status = when (e.reason) {
            CommerceFailure.AUTHENTICATION_REQUIRED -> HttpStatus.UNAUTHORIZED
            CommerceFailure.USER_MISMATCH -> HttpStatus.FORBIDDEN
            CommerceFailure.ORDER_NOT_OWNED -> HttpStatus.FORBIDDEN
            CommerceFailure.POINT_BALANCE_OVERFLOW,
            CommerceFailure.INSUFFICIENT_POINTS,
            CommerceFailure.ORDER_ALREADY_CONFIRMED,
            -> HttpStatus.CONFLICT
            CommerceFailure.BRAND_NOT_FOUND,
            CommerceFailure.PRODUCT_NOT_FOUND,
            CommerceFailure.USER_NOT_FOUND,
            CommerceFailure.ORDER_NOT_FOUND,
            -> HttpStatus.NOT_FOUND
            CommerceFailure.BRAND_HAS_ACTIVE_PRODUCTS -> HttpStatus.CONFLICT
            else -> HttpStatus.BAD_REQUEST
        }
        return ResponseEntity(ApiResponse.fail(e.reason.name, e.reason.message), status)
    }

    @ExceptionHandler
    fun handle(e: InsufficientStockException): ResponseEntity<ApiResponse<*>> =
        ResponseEntity(ApiResponse.fail("INSUFFICIENT_STOCK", "재고가 부족합니다."), HttpStatus.CONFLICT)

    @ExceptionHandler
    fun handle(e: CoreException): ResponseEntity<ApiResponse<*>> {
        log.warn("CoreException : {}", e.customMessage ?: e.message, e)
        return failureResponse(errorType = e.errorType, errorMessage = e.customMessage)
    }

    @ExceptionHandler
    fun handleBadRequest(e: MethodArgumentTypeMismatchException, request: HttpServletRequest): ResponseEntity<ApiResponse<*>> {
        val name = e.name
        val type = e.requiredType?.simpleName ?: "unknown"
        val value = e.value ?: "null"
        val message = "요청 파라미터 '$name' (타입: $type)의 값 '$value'이(가) 잘못되었습니다."
        return badRequestResponse(request, message)
    }

    @ExceptionHandler
    fun handleBadRequest(
        e: MissingServletRequestParameterException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiResponse<*>> {
        val name = e.parameterName
        val type = e.parameterType
        val message = "필수 요청 파라미터 '$name' (타입: $type)가 누락되었습니다."
        return badRequestResponse(request, message)
    }

    @ExceptionHandler
    fun handleBadRequest(e: HttpMessageNotReadableException, request: HttpServletRequest): ResponseEntity<ApiResponse<*>> {
        val errorMessage = when (val rootCause = e.rootCause) {
            is InvalidFormatException -> {
                val fieldName = rootCause.path.joinToString(".") { it.fieldName ?: "?" }

                val valueIndicationMessage = when {
                    rootCause.targetType.isEnum -> {
                        val enumClass = rootCause.targetType
                        val enumValues = enumClass.enumConstants.joinToString(", ") { it.toString() }
                        "사용 가능한 값 : [$enumValues]"
                    }

                    else -> ""
                }

                val expectedType = rootCause.targetType.simpleName
                val value = rootCause.value

                "필드 '$fieldName'의 값 '$value'이(가) 예상 타입($expectedType)과 일치하지 않습니다. $valueIndicationMessage"
            }

            is MismatchedInputException -> {
                val fieldPath = rootCause.path.joinToString(".") { it.fieldName ?: "?" }
                "필수 필드 '$fieldPath'이(가) 누락되었습니다."
            }

            is JsonMappingException -> {
                val fieldPath = rootCause.path.joinToString(".") { it.fieldName ?: "?" }
                "필드 '$fieldPath'에서 JSON 매핑 오류가 발생했습니다: ${rootCause.originalMessage}"
            }

            else -> "요청 본문을 처리하는 중 오류가 발생했습니다. JSON 메세지 규격을 확인해주세요."
        }

        return badRequestResponse(request, errorMessage)
    }

    @ExceptionHandler
    fun handleBadRequest(e: ServerWebInputException, request: HttpServletRequest): ResponseEntity<ApiResponse<*>> {
        fun extractMissingParameter(message: String): String {
            val regex = "'(.+?)'".toRegex()
            return regex.find(message)?.groupValues?.get(1) ?: ""
        }

        val missingParams = extractMissingParameter(e.reason ?: "")
        return if (missingParams.isNotEmpty()) {
            badRequestResponse(request, "필수 요청 값 \'$missingParams\'가 누락되었습니다.")
        } else {
            badRequestResponse(request)
        }
    }

    @ExceptionHandler
    fun handleNotFound(e: NoResourceFoundException): ResponseEntity<ApiResponse<*>> {
        return failureResponse(errorType = ErrorType.NOT_FOUND)
    }

    @ExceptionHandler
    fun handle(e: Throwable): ResponseEntity<ApiResponse<*>> {
        log.error("Exception : {}", e.message, e)
        val errorType = ErrorType.INTERNAL_ERROR
        return failureResponse(errorType = errorType)
    }

    private fun failureResponse(errorType: ErrorType, errorMessage: String? = null): ResponseEntity<ApiResponse<*>> =
        ResponseEntity(
            ApiResponse.fail(errorCode = errorType.code, errorMessage = errorMessage ?: errorType.message),
            errorType.status,
        )

    private fun badRequestResponse(request: HttpServletRequest, message: String? = null): ResponseEntity<ApiResponse<*>> {
        if (request.requestURI.startsWith("/api/v1/examples")) {
            return failureResponse(ErrorType.BAD_REQUEST, message)
        }
        return ResponseEntity(ApiResponse.fail("INVALID_REQUEST", message ?: "잘못된 요청입니다."), HttpStatus.BAD_REQUEST)
    }
}
