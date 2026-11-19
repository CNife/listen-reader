package com.cnife.listenreader.core

enum class ErrorKind { NETWORK, AUTH, BALANCE, PERMISSION, RATE_LIMIT, SERVER, NO_KEY, PAGE, PARSE, UNKNOWN }

data class UiError(
    val message: String,
    val kind: ErrorKind,
    val retryable: Boolean = false,
)
