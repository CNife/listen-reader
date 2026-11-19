package com.cnife.listenreader.di

import android.content.Context
import com.cnife.listenreader.service.DefaultReadingService
import com.cnife.listenreader.service.ReadingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** 手工组合根：生产装配在此完成，测试可直接构造 fake ReadingService。 */
class AppContainer(context: Context) {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val libraryStore = InMemoryLibraryStore(context.applicationContext)
    private val settingsStore = InMemorySettingsStore()

    val readingService: ReadingService = DefaultReadingService(
        pageSession = UnavailablePageSession(),
        scriptCleaner = UnconfiguredScriptCleaner(),
        speechSynthesizer = UnconfiguredSpeechSynthesizer(),
        libraryStore = libraryStore,
        playbackEngine = NoopPlaybackEngine(),
        settingsStore = settingsStore,
        timeProvider = SystemTimeProvider(),
        scope = appScope,
    )
}
