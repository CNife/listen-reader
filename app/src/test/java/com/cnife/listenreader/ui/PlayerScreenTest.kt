package com.cnife.listenreader.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cnife.listenreader.core.ErrorKind
import com.cnife.listenreader.core.UiError
import com.cnife.listenreader.model.ClipStatus
import com.cnife.listenreader.model.SegmentKind
import com.cnife.listenreader.model.SourceKind
import com.cnife.listenreader.model.TtsModels
import com.cnife.listenreader.model.Voices
import com.cnife.listenreader.service.AudioTrackInfo
import com.cnife.listenreader.service.PlayerSegment
import com.cnife.listenreader.service.PlayerState
import com.cnife.listenreader.service.ReadingService
import com.cnife.listenreader.testing.FakeReadingService
import com.cnife.listenreader.ui.player.PlayerScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class PlayerScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun playerState() = PlayerState(
        resourceId = "r1",
        title = "测试文章",
        sourceKind = SourceKind.TEXT,
        sourceLabel = "粘贴文本",
        segments = listOf(
            PlayerSegment(0, "第一句朗读文字。", SegmentKind.PARAGRAPH, 1_200L, ClipStatus.READY),
            PlayerSegment(1, "第二句朗读文字。", SegmentKind.PARAGRAPH, 1_000L, ClipStatus.READY),
            PlayerSegment(2, "第三句朗读文字。", SegmentKind.PARAGRAPH, 0L, ClipStatus.SYNTHESIZING),
        ),
        currentIndex = 1,
        positionInSegmentMs = 300L,
        totalDurationMs = 3_400L,
        isPlaying = false,
        activeTrack = AudioTrackInfo("t1", TtsModels.AUDIO3, Voices.WENROU),
    )

    private fun setPlayerScreen(
        service: ReadingService,
        onOpenSettings: () -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                PlayerScreen(
                    service = service,
                    resourceId = "r1",
                    onBack = {},
                    onOpenReader = {},
                    onOpenSettings = onOpenSettings,
                )
            }
        }
    }

    private fun scrollToSegment(index: Int) {
        composeRule.onNodeWithTag("player-lyrics")
            .performScrollToNode(hasTestTag("player-segment-$index"))
        composeRule.waitForIdle()
    }

    @Test
    fun `hero and lyrics render with the current segment highlighted`() {
        val service = FakeReadingService(initialPlayer = playerState())
        setPlayerScreen(service)

        composeRule.onNodeWithTag("player-title").assertTextContains("测试文章")
        composeRule.onNodeWithTag("player-meta").assertTextContains("温柔女声", substring = true)
        scrollToSegment(0)
        scrollToSegment(1)
        composeRule.onNodeWithTag("player-segment-1").assertIsSelected()
        composeRule.onNodeWithTag("player-segment-0").assertIsNotSelected()
        composeRule.onNodeWithText("第二句朗读文字。").assertIsDisplayed()
    }

    @Test
    fun `tapping a lyric row seeks to that segment`() {
        val service = FakeReadingService(initialPlayer = playerState())
        setPlayerScreen(service)

        scrollToSegment(2)
        composeRule.onNodeWithTag("player-segment-2").performClick()
        composeRule.waitForIdle()

        assertEquals(2, service.lastSeekIndex)
        composeRule.onNodeWithTag("player-segment-2").assertIsSelected()
    }

    @Test
    fun `hero play button toggles playback`() {
        val service = FakeReadingService(initialPlayer = playerState())
        setPlayerScreen(service)

        composeRule.onNodeWithTag("player-hero-play").performClick()
        composeRule.waitForIdle()

        assertTrue(service.player.value.isPlaying)
        composeRule.onNodeWithText("暂停").assertIsDisplayed()

        composeRule.onNodeWithTag("player-hero-play").performClick()
        composeRule.waitForIdle()
        assertEquals(false, service.player.value.isPlaying)
    }

    @Test
    fun `speed chip cycles playback speed`() {
        val service = FakeReadingService(initialPlayer = playerState())
        setPlayerScreen(service)

        composeRule.onNodeWithTag("player-speed").performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(1.25f), service.speedChanges)
        composeRule.onNodeWithText("1.25×").assertIsDisplayed()
    }

    @Test
    fun `no key error offers a settings shortcut`() {
        var openedSettings = false
        val state = playerState().copy(
            segments = playerState().segments.map { it.copy(status = ClipStatus.PENDING) },
            error = UiError("尚未配置 StepFun Key，请到设置填写后再播放", ErrorKind.NO_KEY),
        )
        val service = FakeReadingService(initialPlayer = state)
        setPlayerScreen(service, onOpenSettings = { openedSettings = true })

        composeRule.onNodeWithTag("player-lyrics")
            .performScrollToNode(hasTestTag("player-error"))
        composeRule.onNodeWithTag("player-error").assertIsDisplayed()
        composeRule.onNodeWithText("去设置").performClick()
        composeRule.waitForIdle()

        assertTrue(openedSettings)
    }

    @Test
    fun `voice entry opens the voice sheet`() {
        val service = FakeReadingService(initialPlayer = playerState())
        setPlayerScreen(service)

        composeRule.onNodeWithTag("player-voice").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("voice-sheet").assertIsDisplayed()
        composeRule.onNodeWithText("切换音色").assertIsDisplayed()
        composeRule.onNodeWithText("温柔女声（当前）").assertIsDisplayed()
    }

    @Test
    fun `error segment offers a retry action`() {
        val state = playerState().copy(
            segments = playerState().segments.map {
                if (it.index == 1) it.copy(status = ClipStatus.ERROR) else it
            },
        )
        val service = FakeReadingService(initialPlayer = state)
        setPlayerScreen(service)

        scrollToSegment(1)
        composeRule.onNodeWithTag("player-retry-1").performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(1), service.retriedSegments)
    }
}
