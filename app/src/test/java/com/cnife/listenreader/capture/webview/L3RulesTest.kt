package com.cnife.listenreader.capture.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class L3RulesTest {

    private fun candidate(
        index: Int = 0,
        tag: String = "a",
        text: String = "展开全文",
        visible: Boolean = true,
        inForm: Boolean = false,
        href: String? = "javascript:;",
        type: String? = null,
        submit: Boolean = false,
        leaf: Boolean = true,
        width: Double = 80.0,
        height: Double = 32.0,
    ) = L3Candidate(
        index = index,
        tag = tag,
        text = text,
        visible = visible,
        inForm = inForm,
        href = href,
        type = type,
        submit = submit,
        leaf = leaf,
        rectWidth = width,
        rectHeight = height,
    )

    @Test
    fun `matches spec expand phrases`() {
        assertTrue(L3Rules.matchesPhrase("展开全文"))
        assertTrue(L3Rules.matchesPhrase("继续阅读"))
        assertTrue(L3Rules.matchesPhrase("显示更多"))
        assertTrue(L3Rules.matchesPhrase("展开"))
        assertTrue(L3Rules.matchesPhrase("展开更多"))
        assertFalse(L3Rules.matchesPhrase("收起"))
        assertFalse(L3Rules.matchesPhrase("展开更多评论"))
        assertFalse(L3Rules.isSafe(candidate(text = "展开全文及评论")))
    }

    @Test
    fun `safe visible expand control is clickable`() {
        assertTrue(L3Rules.isSafe(candidate()))
        assertEquals(0, L3Rules.pick(listOf(candidate()), emptySet())!!.index)
    }

    @Test
    fun `hidden element is rejected`() {
        assertFalse(L3Rules.isSafe(candidate(visible = false)))
        assertNull(L3Rules.pick(listOf(candidate(visible = false)), emptySet()))
    }

    @Test
    fun `form submit and input-like controls are rejected`() {
        assertFalse(L3Rules.isSafe(candidate(tag = "button", type = "submit", submit = true)))
        assertFalse(L3Rules.isSafe(candidate(tag = "input", type = "button")))
        assertFalse(L3Rules.isSafe(candidate(inForm = true)))
    }

    @Test
    fun `login pay follow download wording is rejected`() {
        listOf("登录后展开全文", "付费阅读全文", "关注后继续阅读", "下载 App 显示更多").forEach { text ->
            assertFalse(text, L3Rules.isSafe(candidate(text = text)))
        }
    }

    @Test
    fun `external link is rejected`() {
        assertFalse(L3Rules.isSafe(candidate(href = "https://example.com/next")))
        assertFalse(L3Rules.isSafe(candidate(href = "/login?next=article")))
        assertTrue(L3Rules.isSafe(candidate(href = "#expand")))
        assertTrue(L3Rules.isSafe(candidate(href = null)))
    }

    @Test
    fun `pick prefers innermost and skips already clicked`() {
        val outer = candidate(index = 0, tag = "div", text = "展开全文", leaf = false, width = 600.0, height = 120.0)
        val inner = candidate(index = 1, tag = "span", text = "展开全文", leaf = true, width = 60.0, height = 20.0)
        assertEquals(1, L3Rules.pick(listOf(outer, inner), emptySet())!!.index)
        assertEquals(0, L3Rules.pick(listOf(outer, inner), setOf(inner.key))!!.index)
        assertNull(L3Rules.pick(listOf(outer, inner), setOf(inner.key, outer.key)))
    }
}
