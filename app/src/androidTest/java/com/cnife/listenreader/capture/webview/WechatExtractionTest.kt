package com.cnife.listenreader.capture.webview

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cnife.listenreader.service.ports.PageExtraction
import com.cnife.listenreader.service.ports.PageSessionState
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WechatExtractionTest {

    private lateinit var context: Context
    private lateinit var testAssets: Context
    private lateinit var server: MockWebServer
    private var session: WebViewPageSession? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        testAssets = InstrumentationRegistry.getInstrumentation().context
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        session?.release()
        server.shutdown()
        server.close()
    }

    @Test
    fun wechatTemplateExtractsTitleBodyAndLiftsLazyImages() {
        enqueueFixture("wechat-article.html")
        val page = open("/mp.weixin.qq.com/s/article")
        assertTrue("expected Ready but was ${page.state.value}", page.state.value is PageSessionState.Ready)

        val extraction = expand(page)

        assertEquals("template:微信公众号", extraction.extractor)
        assertEquals("把通勤时间变成阅读时间", extraction.title)
        assertTrue("hidden paragraph missing", extraction.text.contains("被隐藏的正文段落"))
        assertTrue(extraction.markdown.contains("# 把小屏幕变成耳朵"))
        val html = extraction.html.orEmpty()
        val imgTags = Regex("<img[^>]*>").findAll(html).map { it.value }.toList()
        val src1 = Regex("\\ssrc=\"https://example\\.com/wechat-1\\.jpg\"")
        val src2 = Regex("\\ssrc=\"https://example\\.com/wechat-2\\.jpg\"")
        assertTrue("img tags=$imgTags", imgTags.any { src1.containsMatchIn(it) })
        assertTrue("img tags=$imgTags", imgTags.any { src2.containsMatchIn(it) })
        assertTrue("markdown=${extraction.markdown}", extraction.markdown.contains("wechat-1.jpg"))
        assertTrue("markdown=${extraction.markdown}", extraction.markdown.contains("wechat-2.jpg"))
        assertFalse(extraction.text.contains("二维码"))
        assertFalse(extraction.text.contains("扫码关注"))
        assertFalse(extraction.text.contains("版权所有"))
        assertFalse(extraction.text.contains("阅读 1024"))
        assertFalse(extraction.text.contains("wechatTracking"))
        assertFalse(extraction.needsExpand)
        assertTrue("confidence=${extraction.confidence}", extraction.confidence >= 0.8)
    }

    @Test
    fun l3ClicksVisibleExpandControlAndReextracts() {
        enqueueFixture("wechat-expand.html")
        val page = open("/mp.weixin.qq.com/s/expand")
        assertTrue(page.state.value is PageSessionState.Ready)

        val extraction = expand(page)

        assertTrue("expanded content missing", extraction.text.contains("MARKER 展开后的正文第一段"))
        assertFalse("hidden control must not be clicked", extraction.text.contains("HIDDEN_MARKER"))
        assertFalse("ad link must not be clicked", extraction.text.contains("AD_MARKER"))
        assertEquals(1, extraction.expandRounds)
        assertFalse(
            "needsExpand=${extraction.needsExpand} reason=${extraction.degradedReason} text=${extraction.text}",
            extraction.needsExpand,
        )
        assertTrue("markdown=${extraction.markdown}", extraction.markdown.contains("expanded.jpg"))

        val paths = recordedRequests().mapNotNull { it.path }
        assertTrue("form must not be submitted: $paths", paths.none { it.contains("/submit") })
    }

    @Test
    fun l3StopsAfterTwoRounds() {
        enqueueFixture("wechat-expand-loop.html")
        val page = open("/mp.weixin.qq.com/s/loop")
        assertTrue(page.state.value is PageSessionState.Ready)

        val extraction = expand(page)

        assertTrue(extraction.text.contains("LAYER1"))
        assertTrue(extraction.text.contains("LAYER2"))
        assertFalse("third round must not happen", extraction.text.contains("LAYER3"))
        assertEquals(2, extraction.expandRounds)
        assertTrue(extraction.needsExpand)
    }

    @Test
    fun l3StopsWhenNoSafeCandidateExists() {
        enqueueFixture("wechat-no-expand.html")
        val page = open("/mp.weixin.qq.com/s/no-expand")
        assertTrue(page.state.value is PageSessionState.Ready)

        val extraction = expand(page)

        assertEquals(0, extraction.expandRounds)
        assertTrue(extraction.needsExpand)
    }

    private fun open(path: String): WebViewPageSession {
        val created = WebViewPageSession(context)
        session = created
        val url = server.url(path).toString()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            created.createView(context)
            created.load(url)
        }
        runBlocking {
            withTimeout(TimeUnit.SECONDS.toMillis(30)) {
                created.state.first {
                    it is PageSessionState.Ready ||
                        it is PageSessionState.NeedsUserAction ||
                        it is PageSessionState.Failed
                }
            }
        }
        return created
    }

    private fun expand(page: WebViewPageSession): PageExtraction = runBlocking {
        withTimeout(TimeUnit.SECONDS.toMillis(45)) { page.expandAndExtract() }
    }

    private fun recordedRequests(): List<RecordedRequest> {
        val requests = mutableListOf<RecordedRequest>()
        while (true) {
            val request = server.takeRequest(200, TimeUnit.MILLISECONDS) ?: break
            requests += request
        }
        return requests
    }

    private fun enqueueFixture(name: String) {
        val body = testAssets.assets.open("fixtures/$name").bufferedReader().use { it.readText() }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html; charset=utf-8")
                .setBody(body),
        )
    }
}
