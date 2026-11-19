package com.cnife.listenreader.testing

import android.content.Context
import android.view.View
import com.cnife.listenreader.model.LibraryFilter
import com.cnife.listenreader.model.ThemeMode
import com.cnife.listenreader.service.CaptureMode
import com.cnife.listenreader.service.CaptureState
import com.cnife.listenreader.service.LibraryState
import com.cnife.listenreader.service.PlayerState
import com.cnife.listenreader.service.ReadingService
import com.cnife.listenreader.service.SettingsState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 可配置的 UI 测试替身：实现 ReadingService 全部成员，只在测试 source 使用。
 */
class FakeReadingService(
    initialLibrary: LibraryState = LibraryState(isLoading = false),
    initialSettings: SettingsState = SettingsState(),
) : ReadingService {

    private val _library = MutableStateFlow(initialLibrary)
    override val library: StateFlow<LibraryState> = _library.asStateFlow()

    private val _player = MutableStateFlow(PlayerState())
    override val player: StateFlow<PlayerState> = _player.asStateFlow()

    private val _capture = MutableStateFlow(CaptureState())
    override val capture: StateFlow<CaptureState> = _capture.asStateFlow()

    private val _settings = MutableStateFlow(initialSettings)
    override val settings: StateFlow<SettingsState> = _settings.asStateFlow()

    var lastThemeMode: ThemeMode? = null
        private set

    override fun refreshLibrary() {
        _library.update { it.copy(isLoading = false) }
    }

    override fun setLibraryQuery(query: String) {
        _library.update { it.copy(query = query) }
    }

    override fun setLibraryFilter(filter: LibraryFilter) {
        _library.update { it.copy(filter = filter) }
    }

    override fun openResource(resourceId: String) {
        _player.update { it.copy(resourceId = resourceId) }
    }

    override fun deleteResource(resourceId: String) = Unit

    override fun beginUrlCapture(url: String?) {
        _capture.value = CaptureState(mode = CaptureMode.URL, url = url.orEmpty())
    }

    override fun beginTextCapture(text: String) {
        _capture.value = CaptureState(mode = CaptureMode.TEXT, url = text)
    }

    override fun handleShareIntent(text: String) {
        if (text.contains("http")) beginUrlCapture(text) else beginTextCapture(text)
    }

    override fun loadUrl(url: String) {
        _capture.update { it.copy(mode = CaptureMode.URL, url = url) }
    }

    override fun reloadPage() = Unit

    override fun pageBack(): Boolean = false

    override fun createPageView(context: Context): View? = null

    override fun releasePageView() = Unit

    override fun extractPage() = Unit

    override fun useFullPage() = Unit

    override fun retryL2() = Unit

    override fun updatePreviewTitle(title: String) = Unit

    override fun updatePreviewBody(body: String) = Unit

    override fun saveCapture(): String? = null

    override fun discardCapture() {
        _capture.value = CaptureState()
    }

    override fun play() {
        _player.update { it.copy(isPlaying = true) }
    }

    override fun pause() {
        _player.update { it.copy(isPlaying = false) }
    }

    override fun togglePlay() {
        _player.update { it.copy(isPlaying = !it.isPlaying) }
    }

    override fun nextSegment() {
        _player.update { it.copy(currentIndex = it.currentIndex + 1) }
    }

    override fun previousSegment() {
        _player.update { it.copy(currentIndex = (it.currentIndex - 1).coerceAtLeast(0)) }
    }

    override fun seekToSegment(index: Int) {
        _player.update { it.copy(currentIndex = index) }
    }

    override fun setSpeed(speed: Float) {
        _player.update { it.copy(speed = speed) }
    }

    override fun setSleepTimer(minutes: Int?) {
        _player.update { it.copy(sleepTimerMinutes = minutes) }
    }

    override fun setVoice(voiceId: String) {
        _settings.update { it.copy(voice = voiceId) }
    }

    override fun setModel(modelId: String) {
        _settings.update { it.copy(ttsModel = modelId) }
    }

    override fun retrySegment(index: Int) = Unit

    override fun toggleFollow() {
        _player.update { it.copy(follow = !it.follow) }
    }

    override fun closePlayer() {
        _player.value = PlayerState()
    }

    override fun saveStepFunKey(key: String) {
        _settings.update { it.copy(stepFunKeyConfigured = key.isNotBlank()) }
    }

    override fun saveLlmConfig(endpoint: String, key: String, model: String) {
        _settings.update {
            it.copy(llmEndpoint = endpoint, llmModel = model, llmKeyConfigured = key.isNotBlank())
        }
    }

    override fun setLlmKey(key: String) {
        _settings.update { it.copy(llmKeyConfigured = key.isNotBlank()) }
    }

    override fun setThemeMode(mode: ThemeMode) {
        lastThemeMode = mode
        _settings.update { it.copy(themeMode = mode) }
    }

    override fun setPrefetchCount(count: Int) {
        _settings.update { it.copy(prefetchCount = count) }
    }

    override fun testStepFun() = Unit

    override fun testLlm() = Unit

    override fun previewVoice(voiceId: String) = Unit

    override fun clearOldAudioTracks() = Unit

    override fun refreshStorageUsage() = Unit
}
