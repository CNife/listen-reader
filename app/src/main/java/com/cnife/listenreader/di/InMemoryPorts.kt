package com.cnife.listenreader.di

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import com.cnife.listenreader.model.ThemeMode
import com.cnife.listenreader.service.ports.AppSettings
import com.cnife.listenreader.service.ports.CleanResult
import com.cnife.listenreader.service.ports.ConnectionTestResult
import com.cnife.listenreader.service.ports.PageExtraction
import com.cnife.listenreader.service.ports.PageSession
import com.cnife.listenreader.service.ports.PageSessionState
import com.cnife.listenreader.service.ports.PlaybackEngine
import com.cnife.listenreader.service.ports.PlaybackEngineState
import com.cnife.listenreader.service.ports.ScriptCleaner
import com.cnife.listenreader.service.ports.SettingsStore
import com.cnife.listenreader.service.ports.SpeechSynthesizer
import com.cnife.listenreader.service.ports.TimeProvider
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * T2 期间的生产占位端口：设置保存在内存中（T3 换成 DataStore）。
 * 资料库已改用 Room（见 AppContainer），其余端口等待后续 ticket 替换。
 */
class InMemorySettingsStore : SettingsStore {

    private val state = MutableStateFlow(AppSettings())
    private var stepFunKey: String? = null
    private var llmKey: String? = null

    override val settings: Flow<AppSettings> = state

    override suspend fun setThemeMode(mode: ThemeMode) {
        state.update { it.copy(themeMode = mode) }
    }

    override suspend fun setTtsModel(model: String) {
        state.update { it.copy(ttsModel = model) }
    }

    override suspend fun setVoice(voice: String) {
        state.update { it.copy(voice = voice) }
    }

    override suspend fun setPrefetchCount(count: Int) {
        state.update { it.copy(prefetchCount = count) }
    }

    override suspend fun setPlaybackSpeed(speed: Float) = Unit

    override suspend fun setStepFunKey(key: String) {
        stepFunKey = key.ifBlank { null }
        state.update { it.copy(stepFunKeyConfigured = stepFunKey != null) }
    }

    override suspend fun stepFunKey(): String? = stepFunKey

    override suspend fun setLlmConfig(endpoint: String, model: String, key: String?) {
        llmKey = key
        state.update {
            it.copy(llmEndpoint = endpoint, llmModel = model, llmKeyConfigured = key != null)
        }
    }

    override suspend fun llmEndpoint(): String = state.value.llmEndpoint

    override suspend fun llmModel(): String = state.value.llmModel

    override suspend fun llmKey(): String? = llmKey
}

class SystemTimeProvider : TimeProvider {
    override fun now(): Long = System.currentTimeMillis()
}

/** T6 之前的空页面会话。 */
class UnavailablePageSession : PageSession {
    private val pageState = MutableStateFlow<PageSessionState>(PageSessionState.Idle)
    override val state: StateFlow<PageSessionState> = pageState.asStateFlow()

    override fun createView(context: Context): View = FrameLayout(context)

    override fun load(url: String) {
        pageState.value = PageSessionState.Failed("页面采集将在后续版本接入")
    }

    override fun reload() = Unit

    override fun goBack(): Boolean = false

    override suspend fun extract(): PageExtraction = emptyExtraction()

    override suspend fun expandAndExtract(): PageExtraction = extract()

    override fun release() = Unit

    private fun emptyExtraction() = PageExtraction(
        title = "",
        byline = null,
        publishedAt = null,
        markdown = "",
        text = "",
        pageText = "",
        extractor = "unavailable",
        score = 0.0,
        confidence = 0.0,
        needsExpand = false,
    )
}

/** T4 之前的空 L2 清洗器。 */
class UnconfiguredScriptCleaner : ScriptCleaner {
    override fun isConfigured(): Boolean = false

    override suspend fun clean(title: String, markdown: String): CleanResult =
        CleanResult.Failure("L2 清洗将在后续版本接入")

    override suspend fun testConnection(): ConnectionTestResult =
        ConnectionTestResult(false, "尚未配置 LLM")
}

/** T3 之前的空 TTS 客户端。 */
class UnconfiguredSpeechSynthesizer : SpeechSynthesizer {
    override fun isConfigured(): Boolean = false

    override suspend fun synthesize(text: String, model: String, voice: String, destination: File): Long {
        throw UnsupportedOperationException("StepFun 合成将在 T3 接入")
    }

    override suspend fun testConnection(model: String, voice: String): ConnectionTestResult =
        ConnectionTestResult(false, "尚未配置 StepFun Key")
}

/** T3/T8 之前的空播放引擎。 */
class NoopPlaybackEngine : PlaybackEngine {
    private val engineState = MutableStateFlow<PlaybackEngineState>(PlaybackEngineState.Idle)
    override val state: StateFlow<PlaybackEngineState> = engineState.asStateFlow()

    override suspend fun prepare(file: File, startPositionMs: Long) = Unit

    override fun play() = Unit

    override fun pause() = Unit

    override fun seekTo(ms: Long) = Unit

    override fun setSpeed(speed: Float) = Unit

    override fun release() = Unit
}
