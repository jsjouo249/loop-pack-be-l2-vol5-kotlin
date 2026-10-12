package com.loopers.domain.example

enum class ExampleFailure { INVALID_INPUT, NOT_FOUND }

class ExampleException(val reason: ExampleFailure, message: String) : RuntimeException(message)
