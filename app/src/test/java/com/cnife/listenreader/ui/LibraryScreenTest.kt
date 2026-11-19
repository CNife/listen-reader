package com.cnife.listenreader.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithTag
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
            LibraryScreen(
                service = service,
                onNewCapture = onNew,
                onOpenSettings = onSettings,
                onOpenResource = onOpen,
            )
        }
    }

    @Test
    fun `continue card shows title source progress and opens the resource`() {
        val opened = mutableListOf<String>()
        val service = FakeReadingService(
            initialLibrary = LibraryState(
                isLoading = false,
                items = listOf(item("r1", "列表里的文章")),
                continueListening = item("r1", "上次读的文章", index = 1, segmentCount = 5),
            ),
        )
        show(service, onOpen = { opened += it })

        composeRule.onNodeWithTag("continue-card").assertIsDisplayed()
        composeRule.onNodeWithText("上次读的文章").assertIsDisplayed()
        composeRule.onNodeWithText("粘贴文本 · 第 2 / 5 句").assertIsDisplayed()
        composeRule.onNodeWithTag("continue-progress").assertIsDisplayed()

        composeRule.onNodeWithTag("continue-play").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("r1"), opened)
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

        composeRule.onNodeWithTag("library-search").performTextInput("kotlin")
        composeRule.waitForIdle()

        assertEquals("kotlin", service.library.value.query)
        composeRule.onNodeWithText("Kotlin 入门").assertIsDisplayed()
        composeRule.onNodeWithText("午饭吃什么").assertDoesNotExist()
    }

    @Test
    fun `filter chips narrow the list and mark the selected chip`() {
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
        println("ROOTCOUNT=" + composeRule.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().size)
        println("CONFIRMTAGS=" + composeRule.onAllNodesWithTag("delete-confirm", useUnmergedTree = true).fetchSemanticsNodes().size)
        println("DIALOGTREES=" + composeRule.onAllNodes(androidx.compose.ui.test.hasText("删除这篇内容？", substring = true), useUnmergedTree = true).fetchSemanticsNodes().size)

        composeRule.onNodeWithText("删除这篇内容？").assertExists()
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
        composeRule.onNodeWithText("清除筛选").performClick()
        composeRule.waitForIdle()

        assertEquals("", service.library.value.query)
        assertEquals(LibraryFilter.ALL, service.library.value.filter)
        composeRule.onNodeWithText("还没有内容").assertIsDisplayed()
    }

    @Test
    fun `loading and delete error states are visible`() {
        val loading = FakeReadingService(initialLibrary = LibraryState(isLoading = true))
        composeRule.setContent {
            LibraryScreen(loading, {}, {}, {})
        }
        composeRule.onNodeWithText("正在加载…").assertIsDisplayed()
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

        composeRule.onNodeWithTag("library-error")
            .assertIsDisplayed()
        composeRule.onNodeWithText("删除失败，请稍后重试").assertIsDisplayed()
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
    )
}
