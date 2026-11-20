package com.cnife.listenreader.debug

import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cnife.listenreader.data.db.ListenReaderDatabase
import com.cnife.listenreader.di.DEBUG_PREFS
import com.cnife.listenreader.di.KEY_QWEN_ENDPOINT
import com.cnife.listenreader.di.KEY_STEPFUN_ENDPOINT
import com.cnife.listenreader.di.KEY_UPDATE_ENDPOINT
import com.cnife.listenreader.model.QwenDefaults
import com.cnife.listenreader.model.SourceKind
import com.cnife.listenreader.model.StepFunDefaults
import com.cnife.listenreader.model.ThemeMode
import com.cnife.listenreader.testing.FakeLibraryStore
import com.cnife.listenreader.testing.FakeReadingService
import com.cnife.listenreader.testing.FakeSettingsStore
import com.cnife.listenreader.tts.QwenSpeechSynthesizer
import com.cnife.listenreader.tts.RoutingSpeechSynthesizer
import com.cnife.listenreader.tts.StepFunSpeechSynthesizer
import com.cnife.listenreader.update.GitHubReleaseChecker
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 自验证通道的 JVM 契约：`configure` 真的改端点与设置、`seed_resource` 真的落库、
 * `dump_state` 真的吐出可解析快照、`clear_state` 真的清空库并复原端点。
 * 没有这层断言，Agent 在真机上执行广播时无法分辨「命令生效」与「静默失败」。
 */
@RunWith(AndroidJUnit4::class)
class DebugControlReceiverTest {

    private lateinit var context: Context
    private lateinit var database: ListenReaderDatabase
    private lateinit var settings: FakeSettingsStore
    private lateinit var library: FakeLibraryStore
    private lateinit var service: FakeReadingService
    private lateinit var stepFunSynthesizer: StepFunSpeechSynthesizer
    private lateinit var qwenSynthesizer: QwenSpeechSynthesizer
    private lateinit var synthesizer: RoutingSpeechSynthesizer
    private lateinit var updateChecker: GitHubReleaseChecker

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(DEBUG_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        database = Room.inMemoryDatabaseBuilder(context, ListenReaderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        settings = FakeSettingsStore()
        library = FakeLibraryStore()
        service = FakeReadingService(initialSettings = com.cnife.listenreader.service.SettingsState())
        val cacheDir = File(context.cacheDir, "tts-debug-test")
        stepFunSynthesizer = StepFunSpeechSynthesizer(
            settingsStore = settings,
            cacheDir = cacheDir,
        )
        qwenSynthesizer = QwenSpeechSynthesizer(
            settingsStore = settings,
            cacheDir = cacheDir,
        )
        synthesizer = RoutingSpeechSynthesizer(
            stepFun = stepFunSynthesizer,
            qwen = qwenSynthesizer,
        )
        updateChecker = GitHubReleaseChecker()
    }

    @After
    fun tearDown() {
        database.close()
        context.getSharedPreferences(DEBUG_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun configure_overridesEndpointAndPersistsSettings() {
        val result = runCommand(
            intent("configure")
                .putExtra("stepfun_endpoint", "http://127.0.0.1:18080/")
                .putExtra("qwen_endpoint", "http://127.0.0.1:18080/api/v1/")
                .putExtra("stepfun_key", "mock-stepfun-key")
                .putExtra("qwen_key", "mock-qwen-key")
                .putExtra("llm_endpoint", "http://127.0.0.1:18080/v1")
                .putExtra("llm_model", "mock-model")
                .putExtra("llm_key", "mock-llm-key")
                .putExtra("tts_model", "step-tts-mini")
                .putExtra("voice", "linjiajiejie")
                .putExtra("theme", "dark")
                .putExtra("prefetch_count", 4)
                .putExtra("playback_speed", 1.25f),
        )

        assertTrue(result.boolean("ok"))
        assertEquals("http://127.0.0.1:18080", stepFunSynthesizer.baseUrl)
        assertEquals("http://127.0.0.1:18080/api/v1", qwenSynthesizer.baseUrl)
        assertEquals("http://127.0.0.1:18080", result.string("stepFunEndpoint"))
        assertEquals("http://127.0.0.1:18080/api/v1", result.string("qwenEndpoint"))
        assertEquals(GitHubReleaseChecker.RELEASE_API_URL, result.string("updateEndpoint"))
        assertEquals(
            "http://127.0.0.1:18080",
            context.getSharedPreferences(DEBUG_PREFS, Context.MODE_PRIVATE)
                .getString(KEY_STEPFUN_ENDPOINT, null),
        )
        assertEquals(
            "http://127.0.0.1:18080/api/v1",
            context.getSharedPreferences(DEBUG_PREFS, Context.MODE_PRIVATE)
                .getString(KEY_QWEN_ENDPOINT, null),
        )

        assertEquals("mock-stepfun-key", runBlocking { settings.stepFunKey() })
        assertEquals("mock-qwen-key", runBlocking { settings.qwenKey() })
        assertEquals("mock-llm-key", runBlocking { settings.llmKey() })
        assertEquals("http://127.0.0.1:18080/v1", runBlocking { settings.llmEndpoint() })
        assertEquals("mock-model", runBlocking { settings.llmModel() })
        assertEquals("step-tts-mini", settings.current.ttsModel)
        assertEquals("linjiajiejie", settings.current.voice)
        assertEquals(ThemeMode.DARK, settings.current.themeMode)
        assertEquals(4, settings.current.prefetchCount)
        assertEquals(1.25f, settings.current.playbackSpeed)
    }

    @Test
    fun configure_updateEndpointRedirectsCheckerAndPersists() {
        val mockUrl = "http://127.0.0.1:18080/repos/CNife/listen-reader/releases/latest"
        val result = runCommand(intent("configure").putExtra("update_endpoint", mockUrl))

        assertTrue(result.boolean("ok"))
        assertEquals(mockUrl, updateChecker.releaseApiUrl)
        assertEquals(mockUrl, result.string("updateEndpoint"))
        assertEquals(
            mockUrl,
            context.getSharedPreferences(DEBUG_PREFS, Context.MODE_PRIVATE)
                .getString(KEY_UPDATE_ENDPOINT, null),
        )

        // 空串复原生产端点，否则后续实机调试会一直打本地 mock。
        runCommand(intent("configure").putExtra("update_endpoint", ""))
        assertEquals(GitHubReleaseChecker.RELEASE_API_URL, updateChecker.releaseApiUrl)
    }

    @Test
    fun seedResource_persistsSegmentDrafts() {
        val result = runCommand(
            intent("seed_resource")
                .putExtra("title", "自验证文章")
                .putExtra("text", "第一句足够长的正文。第二句足够长的正文。")
                .putExtra("source_kind", "URL")
                .putExtra("source_url", "https://example.com/debug"),
        )

        assertTrue(result.boolean("ok"))
        val resourceId = result.string("resourceId")
        val created = library.createdResource
        assertEquals(resourceId, created?.id)
        assertEquals("自验证文章", created?.title)
        assertEquals(SourceKind.URL, created?.sourceKind)
        assertEquals("https://example.com/debug", created?.sourceUrl)
        assertTrue("必须把规则分段草稿一并落库", library.savedSegments.isNotEmpty())
        assertEquals(library.savedSegments.size, result.string("segmentCount").toInt())
        assertEquals("debug-seed", created?.extractor)
    }

    @Test
    fun seedResource_withoutTitleUsesPastedTextSemantics() {
        val result = runCommand(intent("seed_resource"))
        assertTrue(result.boolean("ok"))
        val created = library.createdResource
        assertEquals(SourceKind.TEXT, created?.sourceKind)
        assertEquals(DebugCommands.DEFAULT_TITLE, created?.title)
        assertTrue(library.createdSourceText!!.contains("第一句用于验证即时朗读合成"))
    }

    @Test
    fun dumpState_reportsEndpointAndStateSnapshot() {
        runCommand(
            intent("configure")
                .putExtra("stepfun_endpoint", "http://127.0.0.1:18080")
                .putExtra("qwen_endpoint", "http://127.0.0.1:18080/api/v1"),
        )

        val dump = runCommand(intent("dump_state"))
        assertTrue(dump.boolean("ok"))
        assertEquals("http://127.0.0.1:18080", dump.string("stepFunEndpoint"))
        assertEquals("http://127.0.0.1:18080/api/v1", dump.string("qwenEndpoint"))
        assertEquals(GitHubReleaseChecker.RELEASE_API_URL, dump.string("updateEndpoint"))
        val updateJson = dump["settings"]!!.jsonObject["update"]!!.jsonObject
        assertEquals("true", updateJson["autoCheck"]!!.jsonPrimitive.content)
        assertEquals("Idle", updateJson["check"]!!.jsonPrimitive.content)
        val settingsJson = dump["settings"]!!.jsonObject
        // 快照读的是用户当前看到的 UI 状态（由 ReadingService 汇总），不是 SettingsStore 原始值。
        assertEquals("6", settingsJson["prefetchCount"]!!.jsonPrimitive.content)
        assertEquals("SYSTEM", settingsJson["themeMode"]!!.jsonPrimitive.content)
        val playerJson = dump["player"]!!.jsonObject
        assertEquals("0", playerJson["segmentCount"]!!.jsonPrimitive.content)
        assertTrue("dump_state 必须包含 segments 数组", dump.containsKey("segments"))
    }

    @Test
    fun clearState_resetsDatabaseFilesAndEndpoint() {
        runCommand(
            intent("configure")
                .putExtra("stepfun_endpoint", "http://127.0.0.1:18080")
                .putExtra("qwen_endpoint", "http://127.0.0.1:18080/api/v1"),
        )
        runCommand(intent("seed_resource"))
        assertNull("初始时不应记录上次打开", library.lastOpened)

        val cleared = runCommand(intent("clear_state"))
        assertTrue(cleared.boolean("ok"))
        assertEquals(StepFunDefaults.BASE_URL, stepFunSynthesizer.baseUrl)
        assertEquals(QwenDefaults.BASE_URL, qwenSynthesizer.baseUrl)
        assertEquals(GitHubReleaseChecker.RELEASE_API_URL, updateChecker.releaseApiUrl)
        assertNull(
            context.getSharedPreferences(DEBUG_PREFS, Context.MODE_PRIVATE)
                .getString(KEY_STEPFUN_ENDPOINT, null),
        )
        assertNull(
            context.getSharedPreferences(DEBUG_PREFS, Context.MODE_PRIVATE)
                .getString(KEY_QWEN_ENDPOINT, null),
        )
        assertNull(
            context.getSharedPreferences(DEBUG_PREFS, Context.MODE_PRIVATE)
                .getString(KEY_UPDATE_ENDPOINT, null),
        )
        assertFalse("clear_state 必须清理快照目录", File(context.filesDir, "sources").exists())
        assertFalse("clear_state 必须清理试听目录", File(context.filesDir, "previews").exists())
    }

    @Test
    fun unknownCommand_reportsFailureWithoutThrowing() {
        val result = runCommand(intent("nonsense"))
        assertFalse(result.boolean("ok"))
        assertEquals("unknown cmd", result.string("error"))
    }

    private fun runCommand(intent: Intent): JsonObject = runBlocking {
        DebugCommands.execute(
            context = context,
            command = intent.getStringExtra("cmd").orEmpty(),
            intent = intent,
            settingsStore = settings,
            libraryStore = library,
            database = database,
            readingService = service,
            synthesizer = synthesizer,
            updateChecker = updateChecker,
        )
    }.let { Json.parseToJsonElement(it).jsonObject }

    private fun intent(command: String) = Intent().putExtra("cmd", command)

    private fun JsonObject.string(key: String): String = this[key]!!.jsonPrimitive.content
    private fun JsonObject.boolean(key: String): Boolean = this[key]!!.jsonPrimitive.boolean
}
