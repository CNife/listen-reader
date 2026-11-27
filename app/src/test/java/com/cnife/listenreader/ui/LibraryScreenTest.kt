package com.cnife.listenreader.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cnife.listenreader.core.ErrorKind
import com.cnife.listenreader.core.UiError
import com.cnife.listenreader.model.LibraryFilter
import com.cnife.listenreader.model.LibraryItem
import com.cnife.listenreader.model.SourceKind
import com.cnife.listenreader.service.LibraryState
import com.cnife.listenreader.service.ReadingService
import com.cnife.listenreader.testing.FakeReadingService
import com.cnife.listenreader.ui.library.LibraryScreen
import com.cnife.listenreader.ui.theme.ListenReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class LibraryScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun show(
        service: ReadingService,
        onNew: () -> Unit = {},
        onSettings: () -> Unit = {},
        onOpen: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            ListenReaderTheme {
                LibraryScreen(
                    service = service,
                    onNewCapture = onNew,
                    onOpenSettings = onSettings,
                    onOpenResource = onOpen,
                )
            }
        }
    }

    @Test
    fun `continue card shows title source and percent and opens the resource`() {
        val opened = mutableListOf<String>()
        val service = FakeReadingService(
            initialLibrary = LibraryState(
                isLoading = false,
                items = listOf(item("r1", "列表里的文章")),
                continueListening = item("r1", "上次读的文章", index = 1, lastOpenedAt = 1_000L),
            ),
        )
        show(service, onOpen = { opened += it })

        composeRule.onNodeWithTag("continue-card").assertIsDisplayed()
        composeRule.onNodeWithText("上次读的文章").assertIsDisplayed()
        composeRule.onNodeWithText("粘贴文本 · 40%").assertIsDisplayed()
        composeRule.onNodeWithTag("continue-progress", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("继续收听").assertIsDisplayed()

        composeRule.onNodeWithTag("continue-play").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("r1"), opened)
    }

    @Test
    fun `library lists newest first and shows friendly relative time`() {
        val now = System.currentTimeMillis()
        val day = 24L * 60 * 60 * 1000
        val service = FakeReadingService(
            initialLibrary = LibraryState(
                isLoading = false,
                items = listOf(
                    item("old", "旧文章", createdAt = now - 5 * day),
                    item("new", "新文章", createdAt = now - 2 * day),
                ),
            ),
        )
        show(service)

        composeRule.onNodeWithText("2 篇").assertIsDisplayed()
        composeRule.onNodeWithText("粘贴文本 · 2 天前").assertIsDisplayed()

        val newTop = composeRule.onNodeWithTag("library-row-new")
            .fetchSemanticsNode().boundsInRoot.top
        val oldTop = composeRule.onNodeWithTag("library-row-old")
            .fetchSemanticsNode().boundsInRoot.top
        assertTrue(newTop < oldTop)
    }

    @Test
    fun `searching filters the list by title`() {
        val service = FakeReadingService(
            initialLibrary = LibraryState(
                isLoading = false,
                items = listOf(
                    item("a", "Kotlin 入门"),
                    item("b", "午饭吃什么"),
                ),
            ),
        )
        show(service)

        composeRule.onNodeWithTag("library-search").assertDoesNotExist()
        composeRule.onNodeWithTag("library-search-open").performClick()
        composeRule.onNodeWithTag("library-search").performTextInput("kotlin")
        composeRule.waitForIdle()

        assertEquals("kotlin", service.library.value.query)
        composeRule.onNodeWithText("Kotlin 入门").assertIsDisplayed()
        composeRule.onNodeWithText("午饭吃什么").assertDoesNotExist()

        composeRule.onNodeWithTag("library-search-open").performClick()
        composeRule.waitForIdle()
        assertEquals("", service.library.value.query)
        composeRule.onNodeWithTag("library-search").assertDoesNotExist()
    }

    @Test
    fun `filter tabs narrow the list and mark the selected tab`() {
        val service = FakeReadingService(
            initialLibrary = LibraryState(
                isLoading = false,
                items = listOf(
                    item("web", "网页文章", kind = SourceKind.URL),
                    item("text", "粘贴文章", kind = SourceKind.TEXT),
                    item("cached", "缓存文章", cached = true),
                ),
            ),
        )
        show(service)

        composeRule.onNodeWithTag("library-filter-CACHED").performClick()
        composeRule.waitForIdle()

        assertEquals(LibraryFilter.CACHED, service.library.value.filter)
        composeRule.onNodeWithTag("library-filter-CACHED").assertIsSelected()
        composeRule.onNodeWithText("缓存文章").assertIsDisplayed()
        composeRule.onNodeWithText("网页文章").assertDoesNotExist()
        composeRule.onNodeWithText("粘贴文章").assertDoesNotExist()
    }

    @Test
    fun `deleting asks for confirmation and removes the item`() {
        val service = FakeReadingService(
            initialLibrary = LibraryState(
                isLoading = false,
                items = listOf(item("r1", "要删除的文章")),
                continueListening = item("r1", "要删除的文章"),
            ),
        )
        show(service)

        composeRule.onNodeWithTag("library-delete-r1").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("删除这篇内容？").assertExists()
        composeRule.onNodeWithText("会删除朗读文稿、缓存音频和原文快照，无法恢复。").assertExists()
        assertTrue(service.deletedIds.isEmpty())

        composeRule.onNodeWithTag("delete-confirm").performClick()
        composeRule.waitForIdle()

        assertEquals(listOf("r1"), service.deletedIds)
        composeRule.onNodeWithText("要删除的文章").assertDoesNotExist()
        composeRule.onNodeWithText("删除这篇内容？").assertDoesNotExist()
    }

    @Test
    fun `cancelling deletion keeps the item`() {
        val service = FakeReadingService(
            initialLibrary = LibraryState(isLoading = false, items = listOf(item("r1", "保留的文章"))),
        )
        show(service)

        composeRule.onNodeWithTag("library-delete-r1").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("delete-cancel").performClick()
        composeRule.waitForIdle()

        assertTrue(service.deletedIds.isEmpty())
        composeRule.onNodeWithText("保留的文章").assertIsDisplayed()
    }

    @Test
    fun `empty search result offers clearing the filters`() {
        val service = FakeReadingService(
            initialLibrary = LibraryState(isLoading = false, query = "找不到", filter = LibraryFilter.WEB),
        )
        show(service)

        composeRule.onNodeWithText("没有匹配的内容").assertIsDisplayed()
        composeRule.onNodeWithText("换个词，或清除筛选。").assertIsDisplayed()
        composeRule.onNodeWithText("清除筛选").performClick()
        composeRule.waitForIdle()

        assertEquals("", service.library.value.query)
        assertEquals(LibraryFilter.ALL, service.library.value.filter)
        composeRule.onNodeWithText("还没有内容").assertIsDisplayed()
    }

    @Test
    fun `empty library keeps the add action pinned`() {
        val service = FakeReadingService(initialLibrary = LibraryState(isLoading = false))
        var newClicks = 0
        show(service, onNew = { newClicks += 1 })

        composeRule.onNodeWithText("还没有内容").assertIsDisplayed()
        composeRule.onNodeWithText("粘贴一篇长文，或输入网页链接。提取后会生成语音。").assertIsDisplayed()
        composeRule.onNodeWithTag("library-new").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        assertEquals(1, newClicks)
        composeRule.onNodeWithText("添加内容").assertIsDisplayed()
    }

    @Test
    fun `loading state is visible without blocking the page`() {
        val loading = FakeReadingService(initialLibrary = LibraryState(isLoading = true))
        show(loading)

        composeRule.onNodeWithText("正在加载").assertIsDisplayed()
        composeRule.onNodeWithTag("library-new").assertIsDisplayed()
    }

    @Test
    fun `library error message is shown`() {
        val service = FakeReadingService(
            initialLibrary = LibraryState(
                isLoading = false,
                items = listOf(item("r1", "文章")),
                error = UiError("删除失败，请稍后重试", ErrorKind.UNKNOWN, retryable = true),
            ),
        )
        show(service)

        composeRule.onNodeWithTag("library-error").assertIsDisplayed()
        composeRule.onNodeWithText("出错").assertIsDisplayed()
        composeRule.onNodeWithText("删除失败，请稍后重试").assertIsDisplayed()
        composeRule.onNodeWithText("重试").assertIsDisplayed()
        composeRule.onNodeWithText("文章").assertIsDisplayed()
    }

    private fun item(
        id: String,
        title: String,
        kind: SourceKind = SourceKind.TEXT,
        index: Int = 0,
        segmentCount: Int = 5,
        cached: Boolean = false,
        lastOpenedAt: Long = 0,
        createdAt: Long = 1_000L,
    ) = LibraryItem(
        id = id,
        title = title,
        sourceKind = kind,
        sourceLabel = if (kind == SourceKind.TEXT) "粘贴文本" else "example.com",
        segmentCount = segmentCount,
        durationMs = 0,
        hasCachedAudio = cached,
        currentSegmentIndex = index,
        currentPositionMs = 0,
        lastOpenedAt = lastOpenedAt,
        createdAt = createdAt,
    )
}
