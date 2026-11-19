package com.cnife.listenreader.di

import android.content.Context
import android.util.Log
import com.cnife.listenreader.data.db.ListenReaderDatabase
import com.cnife.listenreader.data.library.RoomLibraryStore
import com.cnife.listenreader.l2.LlmScriptCleaner
import com.cnife.listenreader.l2.OpenAiCompatibleClient
import com.cnife.listenreader.service.DefaultReadingService
import com.cnife.listenreader.service.ReadingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** 手工组合根：生产装配在此完成，测试可直接构造 fake ReadingService。 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val l2Log: (String) -> Unit = { line -> Log.d("L2", line) }
    private val l2Client = OpenAiCompatibleClient(logger = l2Log)
    private val database = ListenReaderDatabase.build(appContext)
    private val settingsStore = InMemorySettingsStore()
    private val scriptCleaner = LlmScriptCleaner(
        settingsStore = settingsStore,
        client = l2Client,
        scope = appScope,
        logger = l2Log,
    )
    private val libraryStore = RoomLibraryStore(
        db = database,
        filesDir = appContext.filesDir,
        settingsStore = settingsStore,
    )

    val readingService: ReadingService = DefaultReadingService(
        pageSession = UnavailablePageSession(),
        scriptCleaner = scriptCleaner,
        speechSynthesizer = UnconfiguredSpeechSynthesizer(),
        libraryStore = libraryStore,
        playbackEngine = NoopPlaybackEngine(),
        settingsStore = settingsStore,
        timeProvider = SystemTimeProvider(),
        scope = appScope,
    )
}
