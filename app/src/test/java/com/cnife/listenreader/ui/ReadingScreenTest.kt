package com.cnife.listenreader.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cnife.listenreader.core.ErrorKind
import com.cnife.listenreader.core.UiError
import com.cnife.listenreader.model.ClipStatus
import com.cnife.listenreader.model.SegmentKind
import com.cnife.listenreader.model.SourceKind
import com.cnife.listenreader.service.PlayerSegment
import com.cnife.listenreader.service.PlayerState
import com.cnife.listenreader.testing.FakeReadingService
import com.cnife.listenreader.ui.player.ReadingScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class ReadingScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val snapshot = """
        # 离线原文标题

        这是保存下来的原文第一段，断网也能读。

        > 一段引用原文

        - 列表第一项
        - 列表第二项

        ```
        代码块内容
        ```
    """.trimIndent()

    private fun playerState(
        snapshotText: String? = snapshot,
        snapshotError: String? = null,
    ) = PlayerState(
        resourceId = "r1",
        title = "测试文章",
        sourceKind = SourceKind.URL,
        sourceLabel = "example.com",
        segments = listOf(
            PlayerSegment(0, "文章大标题", SegmentKind.HEADING, 1_000L, ClipStatus.READY),
            PlayerSegment(1, "第一句朗读文字。", SegmentKind.PARAGRAPH, 1_200L, ClipStatus.READY),
            PlayerSegment(2, "一句引用。", SegmentKind.QUOTE, 800L, ClipStatus.READY),
        ),
        currentIndex = 1,
        totalDurationMs = 3_000L,
        snapshotText = snapshotText,
        snapshotError = snapshotError,
    )

    private fun setReadingScreen(service: FakeReadingService, onBack: () -> Unit = {}) {
        composeRule.setContent {
            MaterialTheme {
                ReadingScreen(
                    service = service,
                    resourceId = "r1",
                    onBack = onBack,
                    onOpenSettings = {},
                )
            }
        }
    }

    @Test
    fun `script tab highlights the current segment and tapping another seeks`() {
        val service = FakeReadingService(initialPlayer = playerState())
        setReadingScreen(service)

        composeRule.onNodeWithTag("reader-segment-1").assertIsSelected()
        composeRule.onNodeWithTag("reader-segment-0").assertIsNotSelected()
        composeRule.onNodeWithText("文章大标题").assertIsDisplayed()

        composeRule.onNodeWithTag("reader-segment-2").performClick()
        composeRule.waitForIdle()

        assertEquals(2, service.lastSeekIndex)
        composeRule.onNodeWithTag("reader-segment-2").assertIsSelected()
    }

    @Test
    fun `switching to the snapshot shows the saved markdown offline`() {
        val service = FakeReadingService(initialPlayer = playerState())
        setReadingScreen(service)

        composeRule.onNodeWithTag("reader-script").assertIsDisplayed()
        composeRule.onNodeWithTag("reader-tab-snapshot").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("reader-snapshot").assertIsDisplayed()
        composeRule.onNodeWithText("离线原文标题").assertIsDisplayed()
        composeRule.onNodeWithText("这是保存下来的原文第一段，断网也能读。").assertIsDisplayed()
        composeRule.onNodeWithTag("reader-script").assertDoesNotExist()

        composeRule.onNodeWithTag("reader-tab-script").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("reader-script").assertIsDisplayed()
        composeRule.onNodeWithTag("reader-snapshot").assertDoesNotExist()
    }

    @Test
    fun `missing snapshot degrades to script with a readable notice`() {
        val service = FakeReadingService(
            initialPlayer = playerState(snapshotText = null, snapshotError = "原文快照不可用"),
        )
        setReadingScreen(service)

        composeRule.onNodeWithTag("reader-tab-snapshot").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("reader-snapshot-fallback")
            .assertTextContains("原文快照不可用，已显示朗读文稿")
        composeRule.onNodeWithTag("reader-script").assertIsDisplayed()
        composeRule.onNodeWithTag("reader-snapshot").assertDoesNotExist()
    }

    @Test
    fun `snapshot read failure notice uses the stored reason`() {
        val service = FakeReadingService(
            initialPlayer = playerState(snapshotText = null, snapshotError = "原文快照读取失败"),
        )
        setReadingScreen(service)

        composeRule.onNodeWithTag("reader-tab-snapshot").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("reader-snapshot-fallback")
            .assertTextContains("原文快照读取失败，已显示朗读文稿")
    }

    @Test
    fun `mini player stays available and controls playback`() {
        val service = FakeReadingService(initialPlayer = playerState())
        setReadingScreen(service)

        composeRule.onNodeWithTag("mini-player").assertIsDisplayed()
        composeRule.onNodeWithTag("mini-player-title").assertTextContains("测试文章")
        composeRule.onNodeWithTag("mini-player-current").assertTextContains("第一句朗读文字。")

        composeRule.onNodeWithContentDescription("播放").performClick()
        composeRule.waitForIdle()
        assertTrue(service.player.value.isPlaying)

        composeRule.onNodeWithContentDescription("下一句").performClick()
        composeRule.waitForIdle()
        assertEquals(2, service.player.value.currentIndex)
        composeRule.onNodeWithTag("reader-segment-2").assertIsSelected()

        composeRule.onNodeWithContentDescription("暂停").performClick()
        composeRule.waitForIdle()
        assertFalse(service.player.value.isPlaying)
    }

    @Test
    fun `mini player stays visible while browsing the snapshot`() {
        val service = FakeReadingService(initialPlayer = playerState())
        setReadingScreen(service)

        composeRule.onNodeWithTag("reader-tab-snapshot").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("mini-player").assertIsDisplayed()
        composeRule.onNodeWithTag("mini-player-title").assertTextContains("测试文章")
    }

    @Test
    fun `missing key error offers a settings shortcut in reading mode`() {
        val service = FakeReadingService(
            initialPlayer = playerState().copy(
                error = UiError("还没有配置 StepFun Key，去设置里填一个吧", ErrorKind.NO_KEY),
            ),
        )
        var openedSettings = 0
        composeRule.setContent {
            MaterialTheme {
                ReadingScreen(
                    service = service,
                    resourceId = "r1",
                    onBack = {},
                    onOpenSettings = { openedSettings++ },
                )
            }
        }

        composeRule.onNodeWithTag("reader-error").assertIsDisplayed()
        composeRule.onNodeWithText("还没有配置 StepFun Key，去设置里填一个吧").assertIsDisplayed()
        composeRule.onNodeWithText("去设置").performClick()
        composeRule.waitForIdle()

        assertEquals(1, openedSettings)
    }

    @Test
    fun `retryable error in reading mode retries the current segment`() {
        val service = FakeReadingService(
            initialPlayer = playerState().copy(
                error = UiError("这一句合成失败", ErrorKind.SERVER, retryable = true),
            ),
        )
        setReadingScreen(service)

        composeRule.onNodeWithTag("reader-error").assertIsDisplayed()
        composeRule.onNodeWithText("重试这句").performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(1), service.retriedSegments)
    }

    @Test
    fun `back arrow pops back to the player`() {
        val service = FakeReadingService(initialPlayer = playerState())
        var backCalls = 0
        setReadingScreen(service, onBack = { backCalls++ })

        composeRule.onNodeWithContentDescription("返回").performClick()
        composeRule.waitForIdle()

        assertEquals(1, backCalls)
    }
}
