package com.cnife.listenreader.di

import android.content.Context
import com.cnife.listenreader.data.db.ListenReaderDatabase
import com.cnife.listenreader.data.library.RoomLibraryStore
import com.cnife.listenreader.data.settings.DataStoreSettingsStore
import com.cnife.listenreader.playback.Media3PlaybackEngine
import com.cnife.listenreader.service.DefaultReadingService
import com.cnife.listenreader.service.ReadingService
import com.cnife.listenreader.tts.StepFunSpeechSynthesizer
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** 手工组合根：生产装配在此完成，测试可直接构造 fake ReadingService。 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val database = ListenReaderDatabase.build(appContext)
    private val settingsStore = DataStoreSettingsStore.create(appContext)
    private val libraryStore = RoomLibraryStore(
        db = database,
        filesDir = appContext.filesDir,
        settingsStore = settingsStore,
    )
    private val speechSynthesizer = StepFunSpeechSynthesizer(
        settingsStore = settingsStore,
        cacheDir = File(appContext.cacheDir, "tts"),
    )
    private val playbackEngine = Media3PlaybackEngine(appContext, appScope)

    val readingService: ReadingService = DefaultReadingService(
        pageSession = UnavailablePageSession(),
        scriptCleaner = UnconfiguredScriptCleaner(),
        speechSynthesizer = speechSynthesizer,
        libraryStore = libraryStore,
        playbackEngine = playbackEngine,
        settingsStore = settingsStore,
        timeProvider = SystemTimeProvider(),
        scope = appScope,
    )
}
