package com.cnife.listenreader.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cnife.listenreader.model.TtsModels
import com.cnife.listenreader.model.Voices
import com.cnife.listenreader.service.SettingsState
import com.cnife.listenreader.service.StorageUsage
import com.cnife.listenreader.ui.player.VoiceSheetContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class VoiceSheetTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setContent(
        state: SettingsState = SettingsState(),
        selectedVoice: String = Voices.DEFAULT,
    ): Pair<MutableList<String>, MutableList<String>> {
        val selected = mutableListOf<String>()
        val previewed = mutableListOf<String>()
        composeRule.setContent {
            MaterialTheme {
                VoiceSheetContent(
                    selectedVoice = selectedVoice,
                    state = state,
                    onSelect = { selected += it },
                    onPreview = { previewed += it },
                )
            }
        }
        return selected to previewed
    }

    @Test
    fun `all voices render with the current one selected`() {
        setContent(state = SettingsState(ttsModel = TtsModels.MINI))

        composeRule.onNodeWithText("温柔女声（当前）").assertIsDisplayed()
        composeRule.onNodeWithText("磁性男声").assertIsDisplayed()
        composeRule.onNodeWithText("元气少女").assertIsDisplayed()
        composeRule.onNodeWithText("邻家姐姐").assertIsDisplayed()
        composeRule.onNodeWithText("当前模型：${TtsModels.MINI}").assertIsDisplayed()
    }

    @Test
    fun `selecting a voice and previewing it invoke the callbacks`() {
        val (selected, previewed) = setContent()

        composeRule.onNodeWithTag("voice-option-${Voices.LINJIA}").performClick()
        composeRule.onNodeWithTag("voice-preview-${Voices.YUANQI}").performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(Voices.LINJIA), selected)
        assertEquals(listOf(Voices.YUANQI), previewed)
    }

    @Test
    fun `audition error and progress are shown`() {
        setContent(
            state = SettingsState(
                isAuditioning = true,
                auditionVoice = Voices.CIXING,
                auditionError = "需要联网合成，请联网后重试",
                storage = StorageUsage(0, 0),
            ),
        )

        composeRule.onNodeWithText("正在合成试听音频…").assertIsDisplayed()
        composeRule.onNodeWithTag("voice-audition-error")
            .assertTextContains("需要联网合成", substring = true)
    }
}
