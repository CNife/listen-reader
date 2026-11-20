package com.cnife.listenreader.di

import android.content.Context
import android.util.Log
import com.cnife.listenreader.capture.webview.WebViewPageSession
import com.cnife.listenreader.data.db.ListenReaderDatabase
import com.cnife.listenreader.data.library.RoomLibraryStore
import com.cnife.listenreader.data.settings.DataStoreSettingsStore
import com.cnife.listenreader.l2.LlmScriptCleaner
import com.cnife.listenreader.l2.OpenAiCompatibleClient
import com.cnife.listenreader.model.StepFunDefaults
import com.cnife.listenreader.playback.Media3PlaybackEngine
import com.cnife.listenreader.playback.MediaPlayerAuditionPlayer
import com.cnife.listenreader.service.DefaultReadingService
import com.cnife.listenreader.service.ReadingService
import com.cnife.listenreader.tts.StepFunSpeechSynthesizer
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 手工组合根：生产装配在此完成，测试可直接构造 fake ReadingService。
 *
 * 依赖字段为 `internal`：debug 源集的自验证接收器（`debug/DebugControlReceiver`）
 * 需要运行期注入 mock 端点、预置资源与抓取状态快照；release 构建不包含该接收器。
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext
    internal val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val l2Log: (String) -> Unit = { line -> Log.d("L2", line) }
    private val l2Client = OpenAiCompatibleClient(logger = l2Log)
    internal val database = ListenReaderDatabase.build(appContext)
    internal val settingsStore = DataStoreSettingsStore.create(appContext)
    private val scriptCleaner = LlmScriptCleaner(
        settingsStore = settingsStore,
        client = l2Client,
        scope = appScope,
        logger = l2Log,
    )
    internal val libraryStore = RoomLibraryStore(
        db = database,
        filesDir = appContext.filesDir,
        settingsStore = settingsStore,
    )

    /** debug 接收器持久化的 StepFun 端点覆写；未设置时回到生产默认值。 */
    private val initialStepFunBaseUrl =
        appContext.getSharedPreferences(DEBUG_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_STEPFUN_ENDPOINT, null)
            ?.takeIf { it.isNotBlank() }
            ?: StepFunDefaults.BASE_URL

    internal val speechSynthesizer = StepFunSpeechSynthesizer(
        settingsStore = settingsStore,
        cacheDir = File(appContext.cacheDir, "tts"),
        baseUrl = initialStepFunBaseUrl,
    )
    private val playbackEngine = Media3PlaybackEngine(appContext, appScope)
    private val auditionPlayer = MediaPlayerAuditionPlayer()
    private val previewDir = File(appContext.filesDir, "previews")

    val readingService: ReadingService = DefaultReadingService(
        pageSession = WebViewPageSession(appContext),
        scriptCleaner = scriptCleaner,
        speechSynthesizer = speechSynthesizer,
        libraryStore = libraryStore,
        playbackEngine = playbackEngine,
        settingsStore = settingsStore,
        timeProvider = SystemTimeProvider(),
        scope = appScope,
        auditionPlayer = auditionPlayer,
        previewDir = previewDir,
    )
}

/** debug 自验证通道的共享偏好文件名。 */
internal const val DEBUG_PREFS = "listen_reader_debug"

/** debug 自验证通道持久化的 StepFun 端点键。 */
internal const val KEY_STEPFUN_ENDPOINT = "stepfun_endpoint"
