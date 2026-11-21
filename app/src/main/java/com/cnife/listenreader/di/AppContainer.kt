package com.cnife.listenreader.di

import android.content.Context
import android.util.Log
import com.cnife.listenreader.capture.webview.WebViewPageSession
import com.cnife.listenreader.data.db.ListenReaderDatabase
import com.cnife.listenreader.data.library.RoomLibraryStore
import com.cnife.listenreader.data.settings.DataStoreSettingsStore
import com.cnife.listenreader.l2.LlmScriptCleaner
import com.cnife.listenreader.l2.OpenAiCompatibleClient
import com.cnife.listenreader.model.QwenDefaults
import com.cnife.listenreader.model.StepFunDefaults
import com.cnife.listenreader.playback.Media3PlaybackEngine
import com.cnife.listenreader.playback.MediaPlayerAuditionPlayer
import com.cnife.listenreader.service.DefaultReadingService
import com.cnife.listenreader.service.ReadingService
import com.cnife.listenreader.tts.QwenSpeechSynthesizer
import com.cnife.listenreader.tts.RoutingSpeechSynthesizer
import com.cnife.listenreader.tts.StepFunSpeechSynthesizer
import com.cnife.listenreader.update.GitHubReleaseChecker
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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

    private val initialStepFunBaseUrl =
        appContext.getSharedPreferences(DEBUG_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_STEPFUN_ENDPOINT, null)
            ?.takeIf { it.isNotBlank() }
            ?: StepFunDefaults.BASE_URL

    private val initialQwenBaseUrl =
        appContext.getSharedPreferences(DEBUG_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_QWEN_ENDPOINT, null)
            ?.takeIf { it.isNotBlank() }
            ?: QwenDefaults.BASE_URL

    internal val stepFunSynthesizer = StepFunSpeechSynthesizer(
        settingsStore = settingsStore,
        cacheDir = File(appContext.cacheDir, "tts"),
        baseUrl = initialStepFunBaseUrl,
    )
    internal val qwenSynthesizer = QwenSpeechSynthesizer(
        settingsStore = settingsStore,
        cacheDir = File(appContext.cacheDir, "tts"),
        baseUrl = initialQwenBaseUrl,
    )
    internal val speechSynthesizer = RoutingSpeechSynthesizer(
        stepFun = stepFunSynthesizer,
        qwen = qwenSynthesizer,
    )
    private val playbackEngine = Media3PlaybackEngine(appContext, appScope)
    private val auditionPlayer = MediaPlayerAuditionPlayer()
    private val previewDir = File(appContext.filesDir, "previews")

    private val appVersion: String =
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName.orEmpty()

    private val initialReleaseApiUrl =
        appContext.getSharedPreferences(DEBUG_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_UPDATE_ENDPOINT, null)
            ?.takeIf { it.isNotBlank() }
            ?: GitHubReleaseChecker.RELEASE_API_URL

    internal val updateChecker = GitHubReleaseChecker(releaseApiUrl = initialReleaseApiUrl)

    val readingService: ReadingService = DefaultReadingService(
        pageSession = WebViewPageSession(appContext),
        scriptCleaner = scriptCleaner,
        speechSynthesizer = speechSynthesizer,
        libraryStore = libraryStore,
        playbackEngine = playbackEngine,
        settingsStore = settingsStore,
        timeProvider = SystemTimeProvider(),
        updateChecker = updateChecker,
        appVersion = appVersion,
        scope = appScope,
        auditionPlayer = auditionPlayer,
        previewDir = previewDir,
    )
}

internal const val DEBUG_PREFS = "listen_reader_debug"

internal const val KEY_STEPFUN_ENDPOINT = "stepfun_endpoint"

internal const val KEY_QWEN_ENDPOINT = "qwen_endpoint"

internal const val KEY_UPDATE_ENDPOINT = "update_endpoint"
