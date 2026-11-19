package com.cnife.listenreader.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareTextTest {

    @Test
    fun `share text with a link picks the first http url`() {
        val text = "这篇文章很有意思 https://example.com/post/1?from=share 推荐看看。"
        assertEquals("https://example.com/post/1?from=share", ShareText.firstUrl(text))
    }

    @Test
    fun `trailing chinese punctuation is not part of the url`() {
        assertEquals(
            "https://example.com/a",
            ShareText.firstUrl("看这个：https://example.com/a。还有下文"),
        )
        assertEquals(
            "https://example.com/b",
            ShareText.firstUrl("(https://example.com/b)"),
        )
    }

    @Test
    fun `plain text has no url`() {
        assertNull(ShareText.firstUrl("只有一段文字，没有链接。"))
    }

    @Test
    fun `only absolute http and https urls are accepted`() {
        assertEquals("https://example.com/x", ShareText.normalizeUrl(" https://example.com/x "))
        assertEquals("http://example.com/x", ShareText.normalizeUrl("http://example.com/x"))
        assertNull(ShareText.normalizeUrl("ftp://example.com/x"))
        assertNull(ShareText.normalizeUrl("example.com/x"))
        assertNull(ShareText.normalizeUrl("javascript:alert(1)"))
        assertNull(ShareText.normalizeUrl(""))
    }

    @Test
    fun `source label drops the www prefix`() {
        assertEquals("example.com", ShareText.sourceLabel("https://www.example.com/post/1"))
        assertEquals("mp.weixin.qq.com", ShareText.sourceLabel("https://mp.weixin.qq.com/s/abc"))
        assertEquals("网页", ShareText.sourceLabel("not a url"))
    }
}