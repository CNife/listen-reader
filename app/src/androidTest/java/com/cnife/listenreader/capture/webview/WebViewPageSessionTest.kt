package com.cnife.listenreader.capture.webview

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cnife.listenreader.service.ports.PageExtraction
import com.cnife.listenreader.service.ports.PageExtractionException
import com.cnife.listenreader.service.ports.PageSessionState
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** WebView L1 提取的仪器测试：全部使用本地 MockWebServer fixture，不访问外网。 */
@RunWith(AndroidJUnit4::class)
class WebViewPageSessionTest {

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
    fun extractsLongArticleWithMarkdownAndScore() {
        enqueueFixture("article.html")
        val page = open("/article")
        assertTrue("expected Ready but was ${page.state.value}", page.state.value is PageSessionState.Ready)

        val extraction = extract(page)

        assertTrue(extraction.title.contains("通勤路上听见长文"))
        assertTrue(extraction.extractor == "readability" || extraction.extractor == "density")
        assertTrue(extraction.text.length >= 300)
        assertFalse(extraction.needsExpand)
        assertTrue(extraction.score > 0.3)
        assertTrue(extraction.confidence > 0.3)

        assertTrue(extraction.markdown.contains("# "))
        assertTrue(extraction.markdown.contains("- "))
        assertTrue(extraction.markdown.contains("> "))
        assertTrue(extraction.markdown.contains("https://example.com/reference"))
        assertTrue(extraction.markdown.contains("photo.jpg"))
        assertTrue(extraction.markdown.contains("```"))

        assertTrue(extraction.html!!.contains("photo.jpg"))
        assertFalse(extraction.text.contains("版权所有"))
        assertFalse(extraction.text.contains("tracking script"))
    }

    @Test
    fun shortLinkHeavyPageIsFlaggedForExpansion() {
        enqueueFixture("short-links.html")
        val page = open("/links")
        assertTrue(page.state.value is PageSessionState.Ready)

        val extraction = extract(page)

        assertTrue(extraction.needsExpand)
        assertNotNull(extraction.degradedReason)
        assertTrue(
            extraction.degradedReason!!.contains("正文过短") ||
                extraction.degradedReason!!.contains("链接密度"),
        )
        assertTrue(extraction.confidence <= 0.5)
    }

    @Test
    fun captchaAndLoginWallAsksUserToTakeOver() {
        enqueueFixture("login.html")
        val page = open("/verify")

        assertTrue(page.state.value is PageSessionState.NeedsUserAction)
        val reason = (page.state.value as PageSessionState.NeedsUserAction).reason
        assertTrue(reason.contains("验证"))

        val failure = runCatching { extract(page) }.exceptionOrNull()
        assertTrue(failure is PageExtractionException)
        assertTrue(failure!!.message!!.contains("验证"))
    }

    @Test
    fun waitsForLateDomMutationsBeforeExtracting() {
        enqueueFixture("delayed.html")
        val page = open("/delayed")
        assertTrue(page.state.value is PageSessionState.Ready)

        val extraction = extract(page)

        assertTrue(extraction.text.contains("延迟出现的正文段落"))
    }

    @Test
    fun networkFailureMovesPageToFailed() {
        val created = WebViewPageSession(context)
        session = created
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            created.createView(context)
            created.load("http://127.0.0.1:1/unreachable")
        }
        val settled = runBlocking {
            withTimeout(TimeUnit.SECONDS.toMillis(30)) {
                created.state.first {
                    it is PageSessionState.Ready ||
                        it is PageSessionState.NeedsUserAction ||
                        it is PageSessionState.Failed
                }
            }
        }
        assertTrue("expected Failed but was $settled", settled is PageSessionState.Failed)
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

    private fun extract(page: WebViewPageSession): PageExtraction = runBlocking {
        withTimeout(TimeUnit.SECONDS.toMillis(30)) { page.extract() }
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