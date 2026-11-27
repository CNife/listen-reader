package com.cnife.listenreader.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cnife.listenreader.model.L2Status
import com.cnife.listenreader.model.SegmentDraft
import com.cnife.listenreader.model.SegmentKind
import com.cnife.listenreader.model.SegmentationSource
import com.cnife.listenreader.model.SourceKind
import com.cnife.listenreader.service.CaptureMode
import com.cnife.listenreader.service.CaptureState
import com.cnife.listenreader.service.PreviewState
import com.cnife.listenreader.service.SettingsState
import com.cnife.listenreader.service.TestState
import com.cnife.listenreader.testing.FakeReadingService
import com.cnife.listenreader.ui.capture.L2StatusSection
import com.cnife.listenreader.ui.capture.PreviewScreen
import com.cnife.listenreader.ui.settings.SettingsScreen
import com.cnife.listenreader.ui.theme.ListenReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class L2UiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun notConfigured_showsGuidanceAndOpensSettings() {
        var retryCount = 0
        var settingsCount = 0
        composeRule.setContent {
            ListenReaderTheme {
                L2StatusSection(
                    preview = preview(
                        status = L2Status.NOT_CONFIGURED,
                        segmentation = SegmentationSource.RULE,
                        error = "尚未配置 LLM Key，当前使用规则分段；到设置填写后可重新 L2",
                    ),
                    onRetryL2 = { retryCount++ },
                    onOpenSettings = { settingsCount++ },
                )
            }
        }

        composeRule.onNodeWithTag("preview-status").assertTextContains("AI 清洗", substring = true)
        composeRule.onNodeWithTag("l2-not-configured").assertTextContains("未配置", substring = true)
        composeRule.onNodeWithTag("l2-error").assertTextContains("还没有配置", substring = true)
        composeRule.onNodeWithTag("l2-error").assertTextContains("分段会按规则进行", substring = true)
        composeRule.onNodeWithTag("l2-open-settings").performClick()
        composeRule.onNodeWithTag("l2-retry").performClick()

        assertEquals(1, settingsCount)
        assertEquals(1, retryCount)
    }

    @Test
    fun failed_showsErrorAndRetry() {
        var retryCount = 0
        composeRule.setContent {
            ListenReaderTheme {
                L2StatusSection(
                    preview = preview(
                        status = L2Status.FAILED,
                        segmentation = SegmentationSource.RULE,
                        error = "LLM 返回的清洗结果不是有效 JSON，已重试一次",
                    ),
                    onRetryL2 = { retryCount++ },
                    onOpenSettings = {},
                )
            }
        }

        composeRule.onNodeWithTag("preview-status").assertTextContains("LLM 清洗失败", substring = true)
        composeRule.onNodeWithTag("l2-error").assertTextContains("不是有效 JSON", substring = true)
        composeRule.onNodeWithText("重新清洗").assertIsDisplayed().performClick()
        assertEquals(1, retryCount)
    }

    @Test
    fun running_showsProgress() {
        composeRule.setContent {
            ListenReaderTheme {
                L2StatusSection(
                    preview = preview(L2Status.RUNNING, SegmentationSource.RULE),
                    onRetryL2 = {},
                    onOpenSettings = {},
                )
            }
        }

        composeRule.onNodeWithText("正在用 LLM 清洗并分段…").assertIsDisplayed()
        composeRule.onNodeWithTag("l2-progress").assertIsDisplayed()
    }

    @Test
    fun succeeded_showsLlmSegmentation() {
        composeRule.setContent {
            ListenReaderTheme {
                L2StatusSection(
                    preview = preview(L2Status.SUCCEEDED, SegmentationSource.L2),
                    onRetryL2 = {},
                    onOpenSettings = {},
                )
            }
        }

        composeRule.onNodeWithTag("preview-status").assertTextContains("LLM 分段", substring = true)
        composeRule.onNodeWithTag("l2-segmentation").assertTextContains("已清洗", substring = true)
    }

    @Test
    fun previewSaveButtonAdmitsRuleSegmentationWhenL2Failed() {
        val service = FakeReadingService(
            initialCapture = CaptureState(
                mode = CaptureMode.TEXT,
                preview = preview(
                    status = L2Status.FAILED,
                    segmentation = SegmentationSource.RULE,
                    error = "LLM 返回的清洗结果不是有效 JSON，已重试一次",
                ),
            ),
        )
        composeRule.setContent {
            ListenReaderTheme {
                PreviewScreen(
                    service = service,
                    onBack = {},
                    onSaved = {},
                    onOpenSettings = {},
                )
            }
        }

        composeRule.onNodeWithText("保存（按规则分段）").assertIsDisplayed()
    }

    @Test
    fun settingsLlmSectionSavesEndpointModelAndKey() {
        val service = FakeReadingService(
            initialSettings = SettingsState(
                llmEndpoint = "https://api.example.com/v1",
                llmModel = "my-model",
                llmKeyConfigured = true,
            ),
        )
        composeRule.setContent {
            ListenReaderTheme {
                SettingsScreen(service = service, onBack = {})
            }
        }

        composeRule.onNodeWithTag("llm-settings-section").performScrollTo()
        composeRule.onNodeWithTag("llm-endpoint")
            .performScrollTo()
            .assertTextContains("https://api.example.com/v1")
        composeRule.onNodeWithTag("llm-key").performScrollTo().performTextInput("sk-new")
        composeRule.onNodeWithTag("llm-save").performScrollTo().performClick()
        composeRule.waitForIdle()

        assertEquals(
            Triple("https://api.example.com/v1", "sk-new", "my-model"),
            service.lastLlmSave,
        )

        composeRule.onNodeWithTag("llm-test").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("llm-test-status").assertTextContains("LLM 连接正常")
    }

    @Test
    fun settingsLlmSectionShowsTestFailure() {
        val service = FakeReadingService(
            initialSettings = SettingsState(
                llmKeyConfigured = true,
                llmTest = TestState.Failure("LLM Key 无效或已过期，请到设置检查"),
            ),
        )
        composeRule.setContent {
            ListenReaderTheme {
                SettingsScreen(service = service, onBack = {})
            }
        }

        composeRule.onNodeWithTag("llm-test-status")
            .performScrollTo()
            .assertTextContains("LLM Key 无效", substring = true)
    }

    private fun preview(
        status: L2Status,
        segmentation: SegmentationSource,
        error: String? = null,
    ) = PreviewState(
        title = "文章标题",
        body = "第一段足够长的句子。",
        charCount = 12,
        segmentDrafts = listOf(
            SegmentDraft("文章标题", SegmentKind.HEADING),
            SegmentDraft("第一段足够长的句子。", SegmentKind.PARAGRAPH),
        ),
        segmentation = segmentation,
        l2Status = status,
        l2Error = error,
        sourceKind = SourceKind.TEXT,
        sourceLabel = "粘贴文本",
        extractor = "文本输入",
        score = 1.0,
    )
}
