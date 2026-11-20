package com.cnife.listenreader.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.cnife.listenreader.ListenReaderApp
import com.cnife.listenreader.core.RuleSegmenter
import com.cnife.listenreader.data.db.ListenReaderDatabase
import com.cnife.listenreader.model.Resource
import com.cnife.listenreader.model.SourceKind
import com.cnife.listenreader.model.StepFunDefaults
import com.cnife.listenreader.model.ThemeMode
import com.cnife.listenreader.service.ReadingService
import com.cnife.listenreader.service.ports.LibraryStore
import com.cnife.listenreader.service.ports.SettingsStore
import com.cnife.listenreader.service.ports.SpeechSynthesizer
import com.cnife.listenreader.tts.StepFunSpeechSynthesizer
import com.cnife.listenreader.di.DEBUG_PREFS
import com.cnife.listenreader.di.KEY_STEPFUN_ENDPOINT
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 仅 debug 构建存在的自验证通道：让 Agent 在不改一行源码、不 root 设备的前提下
 * 为真机/模拟器上的真实应用注入 mock 端点、预置资料与抓取状态快照。
 *
 * 用法（见 `docs/agents/verification.md`）：
 * ```
 * adb shell am broadcast -a com.cnife.listenreader.DEBUG_CONTROL \
 *     --es cmd configure --es stepfun_endpoint http://127.0.0.1:18080
 * adb logcat -d -s ListenReaderDebug:I
 * ```
 *
 * 每条命令都把结果输出为单行 JSON（TAG `ListenReaderDebug`），异常一律转成
 * `{"ok":false,...}`，绝不让调试通道把用户进程带崩。
 */
class DebugControlReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val application = context.applicationContext as? ListenReaderApp ?: return
        val container = application.container
        val pending = goAsync()
        val command = intent.getStringExtra("cmd").orEmpty()
        container.appScope.launch {
            val result = try {
                DebugCommands.execute(context.applicationContext, command, intent)
            } catch (t: Throwable) {
                Log.w(TAG, "debug command failed: $command", t)
                buildJsonObject {
                    put("ok", false)
                    put("cmd", command)
                    put("error", t.message ?: t::class.java.simpleName)
                }.toString()
            }
            Log.i(TAG, result)
            pending?.finish()
        }
    }

    companion object {
        const val ACTION = "com.cnife.listenreader.DEBUG_CONTROL"
        const val TAG = "ListenReaderDebug"
    }
}

/**
 * 自验证命令的纯执行层：从 [ListenReaderApp.container] 取出依赖后逐条执行，
 * 便于在 JVM 上（Robolectric）直接以 `runBlocking` 断言每条命令的效果。
 */
internal object DebugCommands {

    suspend fun execute(context: Context, command: String, intent: Intent): String {
        val container = (context.applicationContext as ListenReaderApp).container
        return execute(
            context = context,
            command = command,
            intent = intent,
            settingsStore = container.settingsStore,
            libraryStore = container.libraryStore,
            database = container.database,
            readingService = container.readingService,
            synthesizer = container.speechSynthesizer,
        )
    }

    suspend fun execute(
        context: Context,
        command: String,
        intent: Intent,
        settingsStore: SettingsStore,
        libraryStore: LibraryStore,
        database: ListenReaderDatabase,
        readingService: ReadingService,
        synthesizer: SpeechSynthesizer,
    ): String = when (command) {
        "configure" -> configure(context, intent, settingsStore, synthesizer)
        "seed_resource" -> seedResource(intent, settingsStore, libraryStore, readingService)
        "clear_state" -> clearState(context, libraryStore, database, readingService, synthesizer)
        "dump_state" -> dumpState(synthesizer, readingService)
        else -> result(command, ok = false, error = "unknown cmd")
    }

    private suspend fun configure(
        context: Context,
        intent: Intent,
        settingsStore: SettingsStore,
        synthesizer: SpeechSynthesizer,
    ): String {
        intent.getStringExtra("stepfun_endpoint")?.let { endpoint ->
            val prefs = context.getSharedPreferences(DEBUG_PREFS, Context.MODE_PRIVATE)
            val normalized = endpoint.trim().trimEnd('/')
            if (normalized.isEmpty()) {
                prefs.edit().remove(KEY_STEPFUN_ENDPOINT).apply()
                applyEndpoint(synthesizer, StepFunDefaults.BASE_URL)
            } else {
                prefs.edit().putString(KEY_STEPFUN_ENDPOINT, normalized).apply()
                applyEndpoint(synthesizer, normalized)
            }
        }
        intent.getStringExtra("stepfun_key")?.let { settingsStore.setStepFunKey(it) }
        if (hasAny(intent, "llm_endpoint", "llm_model", "llm_key")) {
            val endpoint = intent.getStringExtra("llm_endpoint") ?: settingsStore.llmEndpoint()
            val model = intent.getStringExtra("llm_model") ?: settingsStore.llmModel()
            val key = intent.getStringExtra("llm_key") ?: settingsStore.llmKey()
            settingsStore.setLlmConfig(endpoint, model, key)
        }
        intent.getStringExtra("tts_model")?.let { settingsStore.setTtsModel(it) }
        intent.getStringExtra("voice")?.let { settingsStore.setVoice(it) }
        intent.getStringExtra("theme")?.let { raw ->
            ThemeMode.entries
                .firstOrNull { it.name.equals(raw, ignoreCase = true) }
                ?.let { settingsStore.setThemeMode(it) }
        }
        if (intent.hasExtra("prefetch_count")) {
            settingsStore.setPrefetchCount(intent.getIntExtra("prefetch_count", DEFAULT_PREFETCH))
        }
        if (intent.hasExtra("playback_speed")) {
            settingsStore.setPlaybackSpeed(intent.getFloatExtra("playback_speed", 1.0f))
        }
        return buildJsonObject {
            put("ok", true)
            put("cmd", "configure")
            put("stepFunEndpoint", synthesizer.endpoint())
        }.toString()
    }

    private suspend fun seedResource(
        intent: Intent,
        settingsStore: SettingsStore,
        libraryStore: LibraryStore,
        readingService: ReadingService,
    ): String {
        val title = intent.getStringExtra("title")?.takeIf { it.isNotBlank() } ?: DEFAULT_TITLE
        val text = intent.getStringExtra("text")?.takeIf { it.isNotBlank() } ?: DEFAULT_TEXT
        val sourceKind = intent.getStringExtra("source_kind")
            ?.let { raw -> SourceKind.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } }
            ?: SourceKind.TEXT
        val sourceUrl = intent.getStringExtra("source_url")
        val now = System.currentTimeMillis()
        val resourceId = UUID.randomUUID().toString()
        val heading = if (sourceKind == SourceKind.TEXT) "" else title
        val drafts = RuleSegmenter.segment(heading, text)
        val resource = Resource(
            id = resourceId,
            title = title,
            sourceKind = sourceKind,
            sourceUrl = sourceUrl,
            byline = null,
            publishedAt = null,
            extractor = "debug-seed",
            snapshotPath = null,
            currentSegmentIndex = 0,
            currentPositionMs = 0,
            activeTrackId = null,
            createdAt = now,
            lastOpenedAt = now,
        )
        libraryStore.createResource(resource, sourceText = text, html = null, segments = drafts)
        readingService.refreshLibrary()
        val open = intent.getBooleanExtra("open", false)
        if (open) readingService.openResource(resourceId)
        return buildJsonObject {
            put("ok", true)
            put("cmd", "seed_resource")
            put("resourceId", resourceId)
            put("segmentCount", drafts.size)
            put("opened", open)
        }.toString()
    }

    private suspend fun clearState(
        context: Context,
        libraryStore: LibraryStore,
        database: ListenReaderDatabase,
        readingService: ReadingService,
        synthesizer: SpeechSynthesizer,
    ): String {
        readingService.closePlayer()
        readingService.discardCapture()
        withContext(Dispatchers.IO) {
            database.clearAllTables()
            File(context.filesDir, "audio").deleteRecursively()
            File(context.filesDir, "sources").deleteRecursively()
            File(context.filesDir, "previews").deleteRecursively()
            File(context.cacheDir, "tts").deleteRecursively()
        }
        context.getSharedPreferences(DEBUG_PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        libraryStore.saveLastOpenedResourceId(null)
        applyEndpoint(synthesizer, StepFunDefaults.BASE_URL)
        readingService.refreshLibrary()
        return result("clear_state", ok = true, error = null)
    }

    private suspend fun dumpState(synthesizer: SpeechSynthesizer, readingService: ReadingService): String {
        val library = readingService.library.value
        val player = readingService.player.value
        val capture = readingService.capture.value
        val settings = readingService.settings.value
        return buildJsonObject {
            put("ok", true)
            put("cmd", "dump_state")
            put("stepFunEndpoint", synthesizer.endpoint())
            put(
                "library",
                buildJsonObject {
                    put("isLoading", library.isLoading)
                    put("itemCount", library.items.size)
                    put("continueId", library.continueListening?.id)
                    put("error", library.error?.message)
                },
            )
            put(
                "player",
                buildJsonObject {
                    put("resourceId", player.resourceId)
                    put("title", player.title)
                    put("currentIndex", player.currentIndex)
                    put("isPlaying", player.isPlaying)
                    put("isPreparing", player.isPreparing)
                    put("segmentCount", player.segments.size)
                    put("readyClipCount", player.segments.count { it.status.name == "READY" })
                    put("generatingClipCount", player.segments.count { it.status.name == "SYNTHESIZING" })
                    put("failedClipCount", player.segments.count { it.status.name == "ERROR" })
                    put("error", player.error?.message)
                },
            )
            put(
                "capture",
                buildJsonObject {
                    put("mode", capture.mode.name)
                    put("url", capture.url)
                    put("isExtracting", capture.isExtracting)
                    put("l2Status", capture.preview?.l2Status?.name)
                    put("previewSegmentCount", capture.preview?.segmentDrafts?.size)
                    put("savedResourceId", capture.savedResourceId)
                    put("error", capture.error?.message)
                },
            )
            put(
                "settings",
                buildJsonObject {
                    put("themeMode", settings.themeMode.name)
                    put("ttsModel", settings.ttsModel)
                    put("voice", settings.voice)
                    put("prefetchCount", settings.prefetchCount)
                    put("stepFunKeyConfigured", settings.stepFunKeyConfigured)
                    put("llmEndpoint", settings.llmEndpoint)
                    put("llmModel", settings.llmModel)
                    put("llmKeyConfigured", settings.llmKeyConfigured)
                },
            )
            put(
                "segments",
                buildJsonArray {
                    player.segments.forEach { segment ->
                        add(
                            buildJsonObject {
                                put("index", segment.index)
                                put("status", segment.status.name)
                                put("text", JsonPrimitive(segment.text.take(40)))
                            },
                        )
                    }
                },
            )
        }.toString()
    }

    private fun applyEndpoint(synthesizer: SpeechSynthesizer, endpoint: String) {
        (synthesizer as? StepFunSpeechSynthesizer)?.baseUrl = endpoint
    }

    private fun SpeechSynthesizer.endpoint(): String =
        (this as? StepFunSpeechSynthesizer)?.baseUrl ?: StepFunDefaults.BASE_URL

    private fun result(command: String, ok: Boolean, error: String?): String = buildJsonObject {
        put("ok", ok)
        put("cmd", command)
        if (error != null) put("error", error)
    }.toString()

    private fun hasAny(intent: Intent, vararg keys: String): Boolean = keys.any { intent.hasExtra(it) }

    const val DEFAULT_PREFETCH = 6
    const val DEFAULT_TITLE = "调试测试文章"
    const val DEFAULT_TEXT =
        "第一句用于验证即时朗读合成。第二句用于验证后台并行预取队列。" +
            "第三句用于验证跨语段自动推进。第四句用于验证全部语段缓存就绪。"
}
