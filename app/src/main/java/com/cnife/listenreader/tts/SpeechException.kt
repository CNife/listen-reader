package com.cnife.listenreader.tts

import com.cnife.listenreader.core.ErrorKind

class SpeechException(
    val kind: ErrorKind,
    override val message: String,
    val retryable: Boolean = false,
    cause: Throwable? = null,
) : Exception(message, cause)
