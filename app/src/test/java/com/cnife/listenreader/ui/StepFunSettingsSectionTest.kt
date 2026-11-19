package com.cnife.listenreader.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cnife.listenreader.model.TtsModels
import com.cnife.listenreader.model.Voices
import com.cnife.listenreader.service.TestState
import com.cnife.listenreader.testing.FakeReadingService
import com.cnife.listenreader.ui.settings.StepFunSettingsSection
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class StepFunSettingsSectionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setSection(service: FakeReadingService) {
        composeRule.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    StepFunSettingsSection(service = service)
                }
            }
        }
    }

    @Test
    fun `saving a key updates the configured status`() {
        val service = FakeReadingService()
        setSection(service)

        composeRule.onNodeWithTag("stepfun-key-status").assertTextContains("尚未配置 Key", substring = true)
        composeRule.onNodeWithTag("stepfun-key").performTextInput("sk-ui-test")
        composeRule.onNodeWithTag("stepfun-save-key").performClick()
        composeRule.waitForIdle()

        assertEquals("sk-ui-test", service.lastSavedStepFunKey)
        composeRule.onNodeWithTag("stepfun-key-status").assertTextContains("已加密保存", substring = true)
    }

    @Test
    fun `selecting model and voice writes the global settings`() {
        val service = FakeReadingService()
        setSection(service)

        composeRule.onNodeWithTag("tts-model-${TtsModels.MINI}").performScrollTo().performClick()
        composeRule.onNodeWithTag("voice-${Voices.LINJIA}").performScrollTo().performClick()
        composeRule.waitForIdle()

        assertEquals(TtsModels.MINI, service.settings.value.ttsModel)
        assertEquals(Voices.LINJIA, service.settings.value.voice)
    }

    @Test
    fun `test connection shows success and failure states`() {
        val service = FakeReadingService()
        setSection(service)

        service.stepFunTestResult = TestState.Success("连接成功，已合成试听音频")
        composeRule.onNodeWithTag("stepfun-test").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("stepfun-test-status")
            .assertTextContains("连接成功", substring = true)

        service.stepFunTestResult = TestState.Failure("StepFun Key 无效或已过期")
        composeRule.onNodeWithTag("stepfun-test").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("stepfun-test-status")
            .assertTextContains("无效", substring = true)
    }

    @Test
    fun `settings screen exposes the StepFun form`() {
        val service = FakeReadingService()
        composeRule.setContent {
            MaterialTheme {
                com.cnife.listenreader.ui.settings.SettingsScreen(service = service, onBack = {})
            }
        }
        composeRule.onNodeWithTag("stepfun-key").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("StepFun 朗读").assertIsDisplayed()
    }
}
