package com.cnife.listenreader.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cnife.listenreader.model.LlmDefaults
import com.cnife.listenreader.model.ThemeMode
import com.cnife.listenreader.model.TtsModels
import com.cnife.listenreader.model.Voices
import com.cnife.listenreader.service.ports.AppSettings
import com.cnife.listenreader.service.ports.RemoteRelease
import com.cnife.listenreader.service.ports.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "listen-reader-settings")

class DataStoreSettingsStore(
    private val dataStore: DataStore<Preferences>,
    private val keyStore: EncryptedKeyStore = EncryptedKeyStore(),
) : SettingsStore {

    override val settings: Flow<AppSettings> = dataStore.data.map { it.toAppSettings(keyStore) }

    override suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[Keys.THEME] = mode.name }
    }

    override suspend fun setTtsModel(model: String) {
        dataStore.edit { it[Keys.TTS_MODEL] = model }
    }

    override suspend fun setVoice(voice: String) {
        dataStore.edit { it[Keys.VOICE] = voice }
    }

    override suspend fun setPrefetchCount(count: Int) {
        dataStore.edit { it[Keys.PREFETCH] = count.coerceIn(0, 8) }
    }

    override suspend fun setPlaybackSpeed(speed: Float) {
        dataStore.edit { it[Keys.PLAYBACK_SPEED] = speed.coerceIn(0.5f, 2.0f) }
    }

    override suspend fun setStepFunKey(key: String) {
        if (key.isBlank()) {
            dataStore.edit { it.remove(Keys.STEPFUN_KEY) }
            return
        }
        val encrypted = keyStore.encrypt(key.trim())
            ?: throw IllegalStateException("无法加密 StepFun Key")
        dataStore.edit { it[Keys.STEPFUN_KEY] = encrypted }
    }

    override suspend fun stepFunKey(): String? =
        keyStore.decrypt(dataStore.data.first()[Keys.STEPFUN_KEY])

    override suspend fun setQwenKey(key: String) {
        if (key.isBlank()) {
            dataStore.edit { it.remove(Keys.QWEN_KEY) }
            return
        }
        val encrypted = keyStore.encrypt(key.trim())
            ?: throw IllegalStateException("无法加密千问 Key")
        dataStore.edit { it[Keys.QWEN_KEY] = encrypted }
    }

    override suspend fun qwenKey(): String? =
        keyStore.decrypt(dataStore.data.first()[Keys.QWEN_KEY])
    override suspend fun setLlmConfig(endpoint: String, model: String, key: String?) {
        val trimmed = key?.takeIf { it.isNotBlank() }?.trim()
        val encrypted = trimmed?.let { keyStore.encrypt(it) }
        if (trimmed != null && encrypted == null) {
            throw IllegalStateException("无法加密 LLM Key")
        }
        dataStore.edit { prefs ->
            prefs[Keys.LLM_ENDPOINT] = endpoint
            prefs[Keys.LLM_MODEL] = model
            if (encrypted == null) prefs.remove(Keys.LLM_KEY) else prefs[Keys.LLM_KEY] = encrypted
        }
    }

    override suspend fun llmEndpoint(): String =
        dataStore.data.first()[Keys.LLM_ENDPOINT] ?: LlmDefaults.ENDPOINT

    override suspend fun llmModel(): String =
        dataStore.data.first()[Keys.LLM_MODEL] ?: LlmDefaults.MODEL

    override suspend fun llmKey(): String? =
        keyStore.decrypt(dataStore.data.first()[Keys.LLM_KEY])

    override suspend fun setAutoCheckUpdate(enabled: Boolean) {
        dataStore.edit { it[Keys.AUTO_CHECK_UPDATE] = enabled }
    }

    override suspend fun saveReleaseCheck(atMs: Long, release: RemoteRelease) {
        dataStore.edit { prefs ->
            prefs[Keys.LAST_UPDATE_CHECK] = atMs
            prefs[Keys.RELEASE_VERSION] = release.version
            prefs[Keys.RELEASE_URL] = release.url
            prefs[Keys.RELEASE_NOTES] = release.notes
        }
    }

    override suspend fun setIgnoredUpdateVersion(version: String?) {
        dataStore.edit { prefs ->
            if (version == null) prefs.remove(Keys.IGNORED_UPDATE_VERSION)
            else prefs[Keys.IGNORED_UPDATE_VERSION] = version
        }
    }

    companion object {
        fun create(context: Context): DataStoreSettingsStore =
            DataStoreSettingsStore(context.applicationContext.settingsDataStore)
    }
}

private object Keys {
    val THEME = stringPreferencesKey("themeMode")
    val TTS_MODEL = stringPreferencesKey("ttsModel")
    val VOICE = stringPreferencesKey("voice")
    val PREFETCH = intPreferencesKey("prefetchCount")
    val PLAYBACK_SPEED = floatPreferencesKey("playbackSpeed")
    val LLM_ENDPOINT = stringPreferencesKey("llmEndpoint")
    val LLM_MODEL = stringPreferencesKey("llmModel")
    val STEPFUN_KEY = stringPreferencesKey("stepFunKeyEncrypted")
    val QWEN_KEY = stringPreferencesKey("qwenKeyEncrypted")
    val LLM_KEY = stringPreferencesKey("llmKeyEncrypted")
    val AUTO_CHECK_UPDATE = booleanPreferencesKey("autoCheckUpdate")
    val LAST_UPDATE_CHECK = longPreferencesKey("lastUpdateCheckAt")
    val RELEASE_VERSION = stringPreferencesKey("knownReleaseVersion")
    val RELEASE_URL = stringPreferencesKey("knownReleaseUrl")
    val RELEASE_NOTES = stringPreferencesKey("knownReleaseNotes")
    val IGNORED_UPDATE_VERSION = stringPreferencesKey("ignoredUpdateVersion")
}

private fun Preferences.toAppSettings(keyStore: EncryptedKeyStore): AppSettings {
    val defaults = AppSettings()
    val normalizedModel = TtsModels.normalize(this[Keys.TTS_MODEL])
    val normalizedVoice = Voices.normalizeForModel(normalizedModel, this[Keys.VOICE])
    return AppSettings(
        themeMode = this[Keys.THEME]
            ?.let { stored -> runCatching { ThemeMode.valueOf(stored) }.getOrNull() }
            ?: ThemeMode.SYSTEM,
        ttsModel = normalizedModel,
        voice = normalizedVoice,
        prefetchCount = (this[Keys.PREFETCH] ?: defaults.prefetchCount).coerceIn(0, 8),
        playbackSpeed = (this[Keys.PLAYBACK_SPEED] ?: defaults.playbackSpeed).coerceIn(0.5f, 2.0f),
        llmEndpoint = this[Keys.LLM_ENDPOINT] ?: LlmDefaults.ENDPOINT,
        llmModel = this[Keys.LLM_MODEL] ?: LlmDefaults.MODEL,
        stepFunKeyConfigured = keyStore.decrypt(this[Keys.STEPFUN_KEY]) != null,
        qwenKeyConfigured = keyStore.decrypt(this[Keys.QWEN_KEY]) != null,
        llmKeyConfigured = keyStore.decrypt(this[Keys.LLM_KEY]) != null,
        autoCheckUpdate = this[Keys.AUTO_CHECK_UPDATE] ?: defaults.autoCheckUpdate,
        lastUpdateCheckAt = this[Keys.LAST_UPDATE_CHECK] ?: defaults.lastUpdateCheckAt,
        knownReleaseVersion = this[Keys.RELEASE_VERSION],
        knownReleaseUrl = this[Keys.RELEASE_URL],
        knownReleaseNotes = this[Keys.RELEASE_NOTES],
        ignoredUpdateVersion = this[Keys.IGNORED_UPDATE_VERSION],
    )
}
