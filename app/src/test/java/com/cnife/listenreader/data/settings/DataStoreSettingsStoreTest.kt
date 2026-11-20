package com.cnife.listenreader.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cnife.listenreader.model.ThemeMode
import com.cnife.listenreader.model.TtsModels
import com.cnife.listenreader.model.Voices
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DataStoreSettingsStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: DataStoreSettingsStore
    private val encryptor = ReversibleEncryptor()

    @After
    fun tearDown() {
        dataStoreScope.cancel()
    }

    private fun createStore(file: File): DataStoreSettingsStore {
        dataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { file },
        )
        store = DataStoreSettingsStore(dataStore, EncryptedKeyStore(encryptor))
        return store
    }

    @Test
    fun `defaults match the product baseline`() = runBlocking {
        val settings = createStore(tempFolder.newFile("settings.preferences_pb")).settings.first()

        assertEquals(ThemeMode.SYSTEM, settings.themeMode)
        assertEquals(TtsModels.MINI, settings.ttsModel)
        assertEquals(Voices.WENROU, settings.voice)
        assertEquals(6, settings.prefetchCount)
        assertEquals(1.0f, settings.playbackSpeed)
        assertFalse(settings.stepFunKeyConfigured)
        assertFalse(settings.qwenKeyConfigured)
        assertFalse(settings.llmKeyConfigured)
    }

    @Test
    fun `values survive and are clamped to supported ranges`() = runBlocking {
        val file = tempFolder.newFile("settings2.preferences_pb")
        createStore(file)

        store.setThemeMode(ThemeMode.DARK)
        store.setTtsModel(TtsModels.MINI)
        store.setVoice(Voices.LINJIA)
        store.setPrefetchCount(9)
        store.setPlaybackSpeed(0.2f)
        store.setLlmConfig("https://example.test/v1", "some/model", "llm-key")

        val settings = store.settings.first()
        assertEquals(ThemeMode.DARK, settings.themeMode)
        assertEquals(TtsModels.MINI, settings.ttsModel)
        assertEquals(Voices.LINJIA, settings.voice)
        assertEquals(8, settings.prefetchCount)
        assertEquals(0.5f, settings.playbackSpeed)
        assertEquals("https://example.test/v1", settings.llmEndpoint)
        assertEquals("some/model", settings.llmModel)
        assertTrue(settings.llmKeyConfigured)
        assertEquals("llm-key", store.llmKey())
    }

    @Test
    fun `keys are stored encrypted and readable only through the encryptor`() = runBlocking {
        val file = tempFolder.newFile("settings3.preferences_pb")
        createStore(file)

        store.setStepFunKey("sk-plain-secret")
        store.setQwenKey("sk-qwen-secret")

        val rawStep = dataStore.data.first()[stringPreferencesKey("stepFunKeyEncrypted")]
        val rawQwen = dataStore.data.first()[stringPreferencesKey("qwenKeyEncrypted")]
        assertEquals("enc:sk-plain-secret", rawStep)
        assertEquals("enc:sk-qwen-secret", rawQwen)
        assertEquals("sk-plain-secret", store.stepFunKey())
        assertEquals("sk-qwen-secret", store.qwenKey())
        assertTrue(store.settings.first().stepFunKeyConfigured)
        assertTrue(store.settings.first().qwenKeyConfigured)

        store.setStepFunKey("")
        store.setQwenKey("")
        assertNull(store.stepFunKey())
        assertNull(store.qwenKey())
        assertFalse(store.settings.first().stepFunKeyConfigured)
        assertFalse(store.settings.first().qwenKeyConfigured)
    }

    @Test
    fun `legacy model and mismatched voice are normalized on read`() = runBlocking {
        val file = tempFolder.newFile("settings_legacy.preferences_pb")
        createStore(file)
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey("ttsModel")] = "stepaudio-3-tts"
            prefs[stringPreferencesKey("voice")] = Voices.LONGANHUAN
        }

        val migrated = store.settings.first()
        assertEquals(TtsModels.MINI, migrated.ttsModel)
        assertEquals(Voices.WENROU, migrated.voice)

        store.setTtsModel(TtsModels.QWEN_FLASH)
        val qwenSettings = store.settings.first()
        assertEquals(TtsModels.QWEN_FLASH, qwenSettings.ttsModel)
        assertEquals(Voices.LONGANHUAN, qwenSettings.voice)
    }

    @Test
    fun `undecryptable key is treated as not configured`() = runBlocking {
        val file = tempFolder.newFile("settings4.preferences_pb")
        createStore(file)
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey("stepFunKeyEncrypted")] = "corrupted-value"
        }

        assertNull(store.stepFunKey())
        assertFalse(store.settings.first().stepFunKeyConfigured)
    }

    private class ReversibleEncryptor : KeyEncryptor {
        override fun encrypt(plain: String): String = "enc:$plain"
        override fun decrypt(token: String): String? =
            if (token.startsWith("enc:")) token.removePrefix("enc:") else null
    }
}
