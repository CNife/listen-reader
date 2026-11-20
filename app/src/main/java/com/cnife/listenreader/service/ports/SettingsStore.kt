package com.cnife.listenreader.service.ports

import com.cnife.listenreader.model.LlmDefaults
import com.cnife.listenreader.model.ThemeMode
import com.cnife.listenreader.model.TtsModels
import com.cnife.listenreader.model.Voices
import kotlinx.coroutines.flow.Flow

interface SettingsStore {
    val settings: Flow<AppSettings>
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setTtsModel(model: String)
    suspend fun setVoice(voice: String)
    suspend fun setPrefetchCount(count: Int)
    suspend fun setPlaybackSpeed(speed: Float)
    suspend fun setStepFunKey(key: String)
    suspend fun stepFunKey(): String?
    suspend fun setQwenKey(key: String)
    suspend fun qwenKey(): String?
    suspend fun setLlmConfig(endpoint: String, model: String, key: String?)
    suspend fun llmEndpoint(): String
    suspend fun llmModel(): String
    suspend fun llmKey(): String?
    suspend fun setAutoCheckUpdate(enabled: Boolean)

    /** 记录一次成功的更新检查：时间戳与远端版本信息一起落盘，失败时不刷新冷却。 */
    suspend fun saveReleaseCheck(atMs: Long, release: RemoteRelease)
    suspend fun setIgnoredUpdateVersion(version: String?)
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val ttsModel: String = TtsModels.DEFAULT,
    val voice: String = Voices.DEFAULT,
    val prefetchCount: Int = 6,
    val playbackSpeed: Float = 1.0f,
    val llmEndpoint: String = LlmDefaults.ENDPOINT,
    val llmModel: String = LlmDefaults.MODEL,
    val stepFunKeyConfigured: Boolean = false,
    val qwenKeyConfigured: Boolean = false,
    val llmKeyConfigured: Boolean = false,
    val autoCheckUpdate: Boolean = true,
    /** 上次成功检查的时间；0 表示尚未检查，真实时钟下必然已过冷却。 */
    val lastUpdateCheckAt: Long = 0L,
    val knownReleaseVersion: String? = null,
    val knownReleaseUrl: String? = null,
    val knownReleaseNotes: String? = null,
    val ignoredUpdateVersion: String? = null,
)
