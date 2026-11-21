package com.cnife.listenreader.tts

import com.cnife.listenreader.model.TtsModels
import com.cnife.listenreader.model.TtsVendor
import com.cnife.listenreader.service.ports.ConnectionTestResult
import com.cnife.listenreader.service.ports.SpeechSynthesizer
import java.io.File

class RoutingSpeechSynthesizer(
    val stepFun: StepFunSpeechSynthesizer,
    val qwen: QwenSpeechSynthesizer,
) : SpeechSynthesizer {

    override suspend fun synthesize(
        text: String,
        model: String,
        voice: String,
        destination: File,
    ): Long = delegateFor(model).synthesize(text, model, voice, destination)

    override suspend fun testConnection(model: String, voice: String): ConnectionTestResult =
        delegateFor(model).testConnection(model, voice)

    private fun delegateFor(model: String): SpeechSynthesizer = when (TtsModels.vendorOf(model)) {
        TtsVendor.QWEN -> qwen
        TtsVendor.STEPFUN -> stepFun
    }
}
