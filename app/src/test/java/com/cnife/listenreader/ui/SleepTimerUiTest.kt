package com.cnife.listenreader.ui

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cnife.listenreader.model.ClipStatus
import com.cnife.listenreader.model.SegmentKind
import com.cnife.listenreader.model.SourceKind
import com.cnife.listenreader.service.PlayerSegment
import com.cnife.listenreader.service.PlayerState
import com.cnife.listenreader.testing.FakeReadingService
import com.cnife.listenreader.ui.player.PlayerScreen
import com.cnife.listenreader.ui.theme.ListenReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class)
class SleepTimerUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun playerState() = PlayerState(
        resourceId = "r1",
        title = "测试文章",
        sourceKind = SourceKind.TEXT,
        sourceLabel = "粘贴文本",
        segments = listOf(
            PlayerSegment(0, "第一句朗读文字。", SegmentKind.PARAGRAPH, 1_200L, ClipStatus.READY),
        ),
        totalDurationMs = 1_200L,
    )

    private fun setPlayerScreen(service: FakeReadingService) {
        composeRule.setContent {
            ListenReaderTheme {
                PlayerScreen(
                    service = service,
                    resourceId = "r1",
                    onBack = {},
                    onOpenSettings = {},
                )
            }
        }
    }

    @Test
    fun `sleep timer entry offers five fifteen thirty and sixty minutes`() {
        val service = FakeReadingService(initialPlayer = playerState())
        setPlayerScreen(service)

        composeRule.onNodeWithTag("player-sleep-timer").performClick()
        listOf(5, 15, 30, 60).forEach { minutes ->
            composeRule.onNodeWithTag("sleep-option-$minutes", useUnmergedTree = true).assertExists()
        }

        composeRule.onNodeWithTag("sleep-option-15").performClick()
        composeRule.waitForIdle()

        assertEquals(15, service.sleepTimerChanges.last())
        composeRule.onNodeWithTag("player-sleep-timer").assertTextContains("睡眠定时 15:00")
    }

    @Test
    fun `active timer shows remaining time and can be cancelled`() {
        val service = FakeReadingService(
            initialPlayer = playerState().copy(
                sleepTimerMinutes = 5,
                sleepTimerRemainingMs = 299_000L,
            ),
        )
        setPlayerScreen(service)

        composeRule.onNodeWithTag("player-sleep-timer").assertTextContains("睡眠定时 4:59")

        composeRule.onNodeWithTag("player-sleep-timer").performClick()
        composeRule.onNodeWithTag("sleep-cancel").performClick()
        composeRule.waitForIdle()

        assertEquals(null, service.sleepTimerChanges.last())
        composeRule.onNodeWithTag("player-sleep-timer").assertTextContains("睡眠定时")
    }
}
