package com.cnife.listenreader.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cnife.listenreader.ListenReaderApp
import com.cnife.listenreader.model.LibraryItem
import com.cnife.listenreader.model.SourceKind
import com.cnife.listenreader.model.ThemeMode
import com.cnife.listenreader.service.CaptureMode
import com.cnife.listenreader.service.LibraryState
import com.cnife.listenreader.service.ports.PageExtraction
import com.cnife.listenreader.testing.FakeReadingService
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class AppNavHostTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptyLibrary_showsEmptyStateWithNewAndSettingsEntries() {
        val service = FakeReadingService()

        composeRule.setContent { ListenReaderApp(service) }

        composeRule.onNodeWithText("还没有内容").assertIsDisplayed()
        composeRule.onNodeWithText("添加内容").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("设置").assertIsDisplayed()
    }

    @Test
    fun tappingNew_opensCapturePlaceholder() {
        val service = FakeReadingService()
        composeRule.setContent { ListenReaderApp(service) }

        composeRule.onNodeWithText("添加内容").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("添加内容").assertIsDisplayed()
        composeRule.onNodeWithText("粘贴文本").assertIsDisplayed()
        assertEquals(CaptureMode.URL, service.capture.value.mode)
    }

    @Test
    fun emptyUrlCapture_showsPlaceholderInsteadOfWebView() {
        val service = FakeReadingService()
        composeRule.setContent { ListenReaderApp(service) }

        composeRule.onNodeWithText("添加内容").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("capture-url-input").assertIsDisplayed()
        composeRule.onNodeWithTag("capture-empty-web").assertIsDisplayed()
        composeRule.onNodeWithText("输入网址，页面会在这里打开。").assertIsDisplayed()
    }

    @Test
    fun emptyPastedText_showsHint() {
        val service = FakeReadingService()
        composeRule.setContent { ListenReaderApp(service) }

        composeRule.onNodeWithText("添加内容").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("粘贴文本").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("capture-preview").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("请先粘贴要朗读的文本").assertIsDisplayed()
    }

    @Test
    fun pastedText_previewEditAndSave_opensPlayer() {
        val service = FakeReadingService()
        composeRule.setContent { ListenReaderApp(service) }

        composeRule.onNodeWithText("添加内容").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("粘贴文本").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("capture-text-input")
            .performTextInput("文章标题\n\n第一段足够长的句子。\n\n第二段足够长的句子。")
        composeRule.onNodeWithTag("capture-preview").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("preview-title").assertTextContains("文章标题")
        composeRule.onNodeWithTag("preview-status").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("preview-status").assertTextContains("当前按规则分段。")
        composeRule.onNodeWithTag("preview-meta").performScrollTo()
        composeRule.onNodeWithTag("preview-meta").assertTextContains("22 字", substring = true)
        composeRule.onNodeWithTag("preview-meta").assertTextContains("3 句", substring = true)

        composeRule.onNodeWithTag("preview-body")
            .performScrollTo()
            .performTextReplacement("修改后的正文足够长的句子。")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("preview-meta").assertTextContains("2 句", substring = true)

        composeRule.onNodeWithText("保存").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("player-title").assertTextContains("文章标题")
        composeRule.onNodeWithTag("player-hero-play").assertIsDisplayed()
    }

    @Test
    fun urlCapture_showsUrlBarAndExtractNavigatesToPreview() {
        val service = FakeReadingService()
        service.urlExtraction = PageExtraction(
            title = "网页标题",
            byline = null,
            publishedAt = null,
            markdown = "# 网页标题\n\n第一段足够长的网页正文。",
            text = "网页标题\n\n第一段足够长的网页正文。",
            pageText = "网页标题 第一段足够长的网页正文。",
            extractor = "readability",
            score = 0.81,
            confidence = 0.7,
            needsExpand = false,
        )
        composeRule.setContent { ListenReaderApp(service) }

        composeRule.runOnIdle { service.beginUrlCapture("https://www.example.com/post/1") }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("capture-url-input").assertIsDisplayed()
        composeRule.onNodeWithTag("capture-extract").assertIsDisplayed()

        composeRule.onNodeWithTag("capture-extract").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("preview-title").assertTextContains("网页标题")
        composeRule.onNodeWithText("来源：example.com", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun urlCapture_reExtractingAfterBackReturnsToPreview() {
        val service = FakeReadingService()
        service.urlExtraction = PageExtraction(
            title = "网页标题",
            byline = null,
            publishedAt = null,
            markdown = "# 网页标题\n\n第一段足够长的网页正文。",
            text = "网页标题\n\n第一段足够长的网页正文。",
            pageText = "网页标题 第一段足够长的网页正文。",
            extractor = "readability",
            score = 0.81,
            confidence = 0.7,
            needsExpand = false,
        )
        composeRule.setContent { ListenReaderApp(service) }

        composeRule.runOnIdle { service.beginUrlCapture("https://www.example.com/post/1") }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("capture-extract").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("preview-title").assertTextContains("网页标题")

        // 从预览返回采集页编辑后再提取，仍应回到预览而不是停在采集页。
        composeRule.onNodeWithContentDescription("返回").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("capture-extract").assertIsDisplayed()

        composeRule.onNodeWithTag("capture-extract").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("preview-title").assertTextContains("网页标题")
    }

    @Test
    fun library_showsItemsWithSourceAndRelativeTime() {
        val item = LibraryItem(
            id = "r1",
            title = "已保存的文章",
            sourceKind = SourceKind.TEXT,
            sourceLabel = "粘贴文本",
            segmentCount = 5,
            durationMs = 0,
            hasCachedAudio = false,
            currentSegmentIndex = 0,
            currentPositionMs = 0,
            lastOpenedAt = 0,
            createdAt = System.currentTimeMillis() - 86_400_000L,
        )
        val service = FakeReadingService(
            initialLibrary = LibraryState(isLoading = false, items = listOf(item)),
        )
        composeRule.setContent { ListenReaderApp(service) }

        composeRule.onNodeWithText("已保存的文章").assertIsDisplayed()
        composeRule.onNodeWithText("粘贴文本 · 1 天前").assertIsDisplayed()
    }

    @Test
    fun player_opensFromLibrary() {
        val item = LibraryItem(
            id = "r1",
            title = "已保存的文章",
            sourceKind = SourceKind.URL,
            sourceLabel = "example.com",
            segmentCount = 2,
            durationMs = 2_000,
            hasCachedAudio = true,
            currentSegmentIndex = 0,
            currentPositionMs = 0,
            lastOpenedAt = 0,
            createdAt = 1_000L,
        )
        val service = FakeReadingService(
            initialLibrary = LibraryState(isLoading = false, items = listOf(item)),
        )
        composeRule.setContent { ListenReaderApp(service) }

        composeRule.onNodeWithText("已保存的文章").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("player-title").assertTextContains("已保存的文章")
        composeRule.onNodeWithTag("player-open-reader").assertDoesNotExist()
    }

    @Test
    fun settings_themeSwitchTakesEffect() {
        val service = FakeReadingService()
        composeRule.setContent { ListenReaderApp(service) }

        composeRule.onNodeWithContentDescription("设置").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("外观").assertIsDisplayed()

        composeRule.onNodeWithTag("theme-DARK").performClick()
        composeRule.waitForIdle()

        assertEquals(ThemeMode.DARK, service.lastThemeMode)
        composeRule.onNodeWithTag("theme-DARK").assertIsSelected()
        composeRule.onNodeWithTag("theme-SYSTEM").assertIsNotSelected()
    }
}
