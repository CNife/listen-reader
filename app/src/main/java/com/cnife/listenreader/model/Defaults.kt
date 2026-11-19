package com.cnife.listenreader.model

object TtsModels {
    const val AUDIO3 = "stepaudio-3-tts"
    const val MINI = "step-tts-mini"
    const val DEFAULT = AUDIO3
    val ALL = listOf(AUDIO3, MINI)
}

object Voices {
    const val WENROU = "wenrounvsheng"
    const val CIXING = "cixingnansheng"
    const val YUANQI = "yuanqishaonv"
    const val LINJIA = "linjiajiejie"
    const val DEFAULT = WENROU
    val ALL = listOf(WENROU, CIXING, YUANQI, LINJIA)
    val DISPLAY = mapOf(
        WENROU to "温柔女声",
        CIXING to "磁性男声",
        YUANQI to "元气少女",
        LINJIA to "邻家姐姐",
    )
}

object LlmDefaults {
    const val ENDPOINT = "https://openrouter.ai/api/v1"
    const val MODEL = "openai/gpt-4.1-mini"
}

object StepFunDefaults {
    const val BASE_URL = "https://api.stepfun.com/v1"
    const val SAMPLE_RATE = 24000
}
