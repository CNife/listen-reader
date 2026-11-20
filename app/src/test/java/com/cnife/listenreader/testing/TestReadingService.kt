package com.cnife.listenreader.testing

import com.cnife.listenreader.service.DefaultReadingService
import com.cnife.listenreader.service.ports.AuditionPlayer
import com.cnife.listenreader.service.ports.LibraryStore
import com.cnife.listenreader.service.ports.NoopAuditionPlayer
import com.cnife.listenreader.service.ports.PageSession
import com.cnife.listenreader.service.ports.PlaybackEngine
import com.cnife.listenreader.service.ports.ScriptCleaner
import com.cnife.listenreader.service.ports.SettingsStore
import com.cnife.listenreader.service.ports.SpeechSynthesizer
import com.cnife.listenreader.service.ports.TimeProvider
import com.cnife.listenreader.service.ports.UpdateChecker
import java.io.File
import kotlinx.coroutines.CoroutineScope

/**
 * 服务层流程测试的唯一装配点：`DefaultReadingService` 每加一个端口，只需改这里一次，
 * 而不是在 9 个流程测试里各改一份 10 参数的构造调用并互相漂移。
 *
 * 调用方自己持有 [scope]，并在测试结束时取消它；工厂只负责把端口接满。
 */
fun testReadingService(
    scope: CoroutineScope,
    pageSession: PageSession = FakePageSession(),
    scriptCleaner: ScriptCleaner = FakeScriptCleaner(),
    speechSynthesizer: SpeechSynthesizer = FakeSpeechSynthesizer(),
    libraryStore: LibraryStore = FakeLibraryStore(),
    playbackEngine: PlaybackEngine = FakePlaybackEngine(),
    settingsStore: SettingsStore = FakeSettingsStore(),
    timeProvider: TimeProvider = FixedTimeProvider(),
    updateChecker: UpdateChecker = FakeUpdateChecker(),
    appVersion: String = "0.1.0",
    auditionPlayer: AuditionPlayer = NoopAuditionPlayer,
    previewDir: File? = null,
): DefaultReadingService = DefaultReadingService(
    pageSession = pageSession,
    scriptCleaner = scriptCleaner,
    speechSynthesizer = speechSynthesizer,
    libraryStore = libraryStore,
    playbackEngine = playbackEngine,
    settingsStore = settingsStore,
    timeProvider = timeProvider,
    updateChecker = updateChecker,
    appVersion = appVersion,
    scope = scope,
    auditionPlayer = auditionPlayer,
    previewDir = previewDir,
)
