package com.cnife.listenreader.ui

import android.Manifest
import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cnife.listenreader.model.ClipStatus
import com.cnife.listenreader.model.SegmentKind
import com.cnife.listenreader.model.SourceKind
import com.cnife.listenreader.service.PlayerSegment
import com.cnife.listenreader.service.PlayerState
import com.cnife.listenreader.testing.FakeReadingService
import com.cnife.listenreader.ui.player.NOTIFICATION_PERMISSION_HINT
import com.cnife.listenreader.ui.player.PlayerScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class NotificationPermissionUiTest {

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

    private fun setPlayerScreen() {
        val service = FakeReadingService(initialPlayer = playerState())
        composeRule.setContent {
            MaterialTheme {
                PlayerScreen(
                    service = service,
                    resourceId = "r1",
                    onBack = {},
                    onOpenReader = {},
                    onOpenSettings = {},
                )
            }
        }
    }

    @Test
    fun `denied notifications show a readable lock screen hint`() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        setPlayerScreen()

        composeRule.onNodeWithTag("player-lyrics")
            .performScrollToNode(hasTestTag("notification-permission-hint"))
        composeRule.onNodeWithTag("notification-permission-hint").assertIsDisplayed()
        composeRule.onNodeWithText(NOTIFICATION_PERMISSION_HINT).assertIsDisplayed()
        composeRule.onNodeWithTag("notification-open-settings").assertIsDisplayed()
    }

    @Test
    fun `granted notifications hide the hint`() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        setPlayerScreen()

        composeRule.onNodeWithTag("notification-permission-hint").assertDoesNotExist()
    }
}
