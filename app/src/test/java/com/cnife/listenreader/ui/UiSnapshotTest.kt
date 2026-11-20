package com.cnife.listenreader.ui

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cnife.listenreader.ListenReaderApp
import com.cnife.listenreader.core.ErrorKind
import com.cnife.listenreader.core.UiError
import com.cnife.listenreader.model.ClipStatus
import com.cnife.listenreader.model.L2Status
import com.cnife.listenreader.model.LibraryItem
import com.cnife.listenreader.model.SegmentDraft
import com.cnife.listenreader.model.SegmentKind
import com.cnife.listenreader.model.SegmentationSource
import com.cnife.listenreader.model.SourceKind
import com.cnife.listenreader.model.ThemeMode
import com.cnife.listenreader.service.CaptureMode
import com.cnife.listenreader.service.CaptureState
import com.cnife.listenreader.service.LibraryState
import com.cnife.listenreader.service.PlayerSegment
import com.cnife.listenreader.service.PlayerState
import com.cnife.listenreader.service.PreviewState
import com.cnife.listenreader.service.SettingsState
import com.cnife.listenreader.testing.FakeReadingService
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * JVM 级 Compose 视觉快照：Robolectric Native Graphics + Roborazzi 把真实界面渲染成 PNG，
 * 供多模态 Agent 直接用 `read` 查看布局、留白、深浅色对比与状态文案。
 *
 * 快照写入 `app/build/outputs/roborazzi/`，不进版本库（避免跨平台字体差异导致 CI 误报）。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class UiSnapshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun snapshot_libraryEmpty() {
        render(FakeReadingService())
        captureSnapshot("library-empty")
    }

    @Test
    fun snapshot_libraryEmptyDark() {
        render(
            FakeReadingService(
                initialSettings = SettingsState(themeMode = ThemeMode.DARK),
            ),
        )
        captureSnapshot("library-empty-dark")
    }

    @Test
    fun snapshot_libraryError() {
        render(
            FakeReadingService(
                initialLibrary = LibraryState(
                    isLoading = false,
                    error = UiError("删除失败，请稍后重试。", ErrorKind.UNKNOWN, retryable = true),
                ),
            ),
        )
        captureSnapshot("library-error")
    }

    @Test
    fun snapshot_libraryPopulatedWithMiniPlayer() {
        render(
            FakeReadingService(
                initialLibrary = LibraryState(
                    isLoading = false,
                    items = listOf(
                        libraryItem("r1", "在通勤路上听见长文", SourceKind.URL, "example.com", 5, true),
                        libraryItem("r2", "被折叠的公众号文章", SourceKind.URL, "微信公众号", 9, false),
                        libraryItem("r3", "粘贴的深读笔记", SourceKind.TEXT, "粘贴文本", 4, true),
                    ),
                    continueListening = libraryItem("r1", "在通勤路上听见长文", SourceKind.URL, "example.com", 5, true)
                        .copy(lastOpenedAt = 1_000L, currentSegmentIndex = 2),
                ),
                initialPlayer = PlayerState(
                    resourceId = "r1",
                    title = "在通勤路上听见长文",
                    segments = playerSegments(),
                    currentIndex = 1,
                    totalDurationMs = 8_000L,
                    isPlaying = true,
                ),
            ),
        )
        captureSnapshot("library-populated")
    }

    @Test
    fun snapshot_captureUrlIdlePlaceholder() {
        render(FakeReadingService())
        composeRule.onNodeWithText("添加内容").performClick()
        composeRule.waitForIdle()
        captureSnapshot("capture-url-idle")
    }

    @Test
    fun snapshot_previewL2Cleaned() {
        render(
            FakeReadingService(
                initialCapture = CaptureState(
                    mode = CaptureMode.TEXT,
                    sessionId = 1,
                    previewRevision = 1,
                    preview = PreviewState(
                        title = "把小屏幕变成耳朵",
                        body = "每天早上七点半，地铁里挤满了低头看手机的人。把文字变成声音之后，同样的四十分钟忽然变得宽裕起来。",
                        charCount = 66,
                        segmentDrafts = listOf(
                            SegmentDraft("每天早上七点半，地铁里挤满了低头看手机的人。", SegmentKind.PARAGRAPH),
                            SegmentDraft("把文字变成声音之后，同样的四十分钟忽然变得宽裕起来。", SegmentKind.PARAGRAPH),
                        ),
                        segmentation = SegmentationSource.L2,
                        l2Status = L2Status.SUCCEEDED,
                        sourceKind = SourceKind.TEXT,
                        sourceLabel = "粘贴文本",
                        extractor = "文本输入",
                        score = 1.0,
                    ),
                ),
            ),
        )
        captureSnapshot("preview-l2-success")
    }

    @Test
    fun snapshot_player() {
        render(
            FakeReadingService(
                initialLibrary = LibraryState(
                    isLoading = false,
                    items = listOf(libraryItem("r1", "在通勤路上听见长文", SourceKind.URL, "example.com", 4, true)),
                ),
                initialPlayer = PlayerState(
                    resourceId = "r1",
                    title = "在通勤路上听见长文",
                    sourceKind = SourceKind.URL,
                    sourceLabel = "example.com",
                    segments = playerSegments(),
                    currentIndex = 1,
                    positionInSegmentMs = 400L,
                    totalDurationMs = 8_000L,
                    isPlaying = true,
                ),
            ),
        )

        composeRule.onNodeWithText("在通勤路上听见长文").performClick()
        composeRule.waitForIdle()
        captureSnapshot("player-active")
    }

    @Test
    fun snapshot_playerNoKey() {
        render(
            FakeReadingService(
                initialLibrary = LibraryState(
                    isLoading = false,
                    items = listOf(libraryItem("r1", "还没有音频的文章", SourceKind.TEXT, "粘贴文本", 3, false)),
                ),
                initialPlayer = PlayerState(
                    resourceId = "r1",
                    title = "还没有音频的文章",
                    sourceKind = SourceKind.TEXT,
                    sourceLabel = "粘贴文本",
                    segments = emptyList(),
                ),
            ),
        )

        composeRule.onNodeWithText("还没有音频的文章").performClick()
        composeRule.waitForIdle()
        captureSnapshot("player-no-key")
    }

    @Test
    fun snapshot_voiceSheet() {
        render(
            FakeReadingService(
                initialLibrary = LibraryState(
                    isLoading = false,
                    items = listOf(libraryItem("r1", "在通勤路上听见长文", SourceKind.URL, "example.com", 4, true)),
                ),
                initialPlayer = PlayerState(
                    resourceId = "r1",
                    title = "在通勤路上听见长文",
                    sourceKind = SourceKind.URL,
                    sourceLabel = "example.com",
                    segments = playerSegments(),
                    totalDurationMs = 8_000L,
                ),
            ),
        )

        composeRule.onNodeWithText("在通勤路上听见长文").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("player-voice").performClick()
        composeRule.waitForIdle()
        captureSnapshot("voice-sheet", nodeTag = "voice-sheet")
    }

    @Test
    fun snapshot_playerFontScale2x() {
        val service = FakeReadingService(
            initialLibrary = LibraryState(
                isLoading = false,
                items = listOf(libraryItem("r1", "在通勤路上听见长文", SourceKind.URL, "example.com", 4, true)),
            ),
            initialPlayer = PlayerState(
                resourceId = "r1",
                title = "在通勤路上听见长文",
                sourceKind = SourceKind.URL,
                sourceLabel = "微信公众号",
                segments = playerSegments(),
                currentIndex = 1,
                positionInSegmentMs = 400L,
                totalDurationMs = 8_000L,
                isPlaying = true,
                activeTrack = com.cnife.listenreader.service.AudioTrackInfo(
                    "t1",
                    "step-tts-mini",
                    "wenrou",
                ),
            ),
        )
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                ListenReaderApp(service)
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("在通勤路上听见长文").performClick()
        composeRule.waitForIdle()
        captureSnapshot("player-font-scale-2x")
    }

    @Test
    fun snapshot_settingsTop() {
        render(
            FakeReadingService(
                initialSettings = SettingsState(
                    prefetchCount = 6,
                    stepFunKeyConfigured = true,
                    llmKeyConfigured = true,
                    llmModel = "openai/gpt-4.1-mini",
                ),
            ),
        )
        composeRule.onNodeWithContentDescription("设置").performClick()
        composeRule.waitForIdle()
        captureSnapshot("settings-top")
    }

    @Test
    fun snapshot_settingsDarkTheme() {
        render(
            FakeReadingService(
                initialSettings = SettingsState(
                    themeMode = ThemeMode.DARK,
                    prefetchCount = 6,
                    stepFunKeyConfigured = true,
                    llmKeyConfigured = true,
                    llmModel = "openai/gpt-4.1-mini",
                ),
            ),
        )
        composeRule.onNodeWithContentDescription("设置").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("预取").performScrollTo()
        captureSnapshot("settings-dark")
    }

    private fun render(service: FakeReadingService) {
        composeRule.setContent { ListenReaderApp(service) }
        composeRule.waitForIdle()
    }

    private fun captureSnapshot(name: String, nodeTag: String? = null) {
        composeRule.waitForIdle()
        val outputDir = File("build/outputs/roborazzi").apply { mkdirs() }
        val file = File(outputDir, "$name.png")
        val node = if (nodeTag != null) {
            composeRule.onNodeWithTag(nodeTag, useUnmergedTree = true)
        } else {
            composeRule.onRoot()
        }
        node.captureRoboImage(file)
        assertTrue("快照未生成：${file.path}", file.isFile && file.length() > 1_000L)
    }

    private fun libraryItem(
        id: String,
        title: String,
        kind: SourceKind,
        label: String,
        segments: Int,
        cached: Boolean,
    ) = LibraryItem(
        id = id,
        title = title,
        sourceKind = kind,
        sourceLabel = label,
        segmentCount = segments,
        durationMs = segments * 2_000L,
        hasCachedAudio = cached,
        currentSegmentIndex = 0,
        currentPositionMs = 0,
        lastOpenedAt = 0,
        createdAt = System.currentTimeMillis() - 86_400_000L,
    )

    private fun playerSegments() = listOf(
        PlayerSegment(0, "导语：把长文交给耳朵。", SegmentKind.HEADING, 1_000L, ClipStatus.READY),
        PlayerSegment(1, "每天早上七点半，地铁里挤满了低头看手机的人。", SegmentKind.PARAGRAPH, 2_000L, ClipStatus.READY),
        PlayerSegment(2, "把文字变成声音之后，同样的四十分钟忽然变得宽裕起来。", SegmentKind.PARAGRAPH, 2_000L, ClipStatus.READY),
        PlayerSegment(3, "技术退到背景里，留下的只有内容本身。", SegmentKind.PARAGRAPH, 2_000L, ClipStatus.SYNTHESIZING),
    )
}
