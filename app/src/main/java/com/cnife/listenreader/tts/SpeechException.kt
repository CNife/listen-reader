package com.cnife.listenreader.tts

import com.cnife.listenreader.core.ErrorKind

/** StepFun 调用失败：错误类别 + 可直接展示的中文文案，不包含 key 或正文。 */
class SpeechException(
    val kind: ErrorKind,
    override val message: String,
    val retryable: Boolean = false,
    cause: Throwable? = null,
) : Exception(message, cause)
