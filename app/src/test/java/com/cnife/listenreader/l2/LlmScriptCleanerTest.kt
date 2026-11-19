package com.cnife.listenreader.l2

import com.cnife.listenreader.model.SegmentKind
import com.cnife.listenreader.service.ports.AppSettings
import com.cnife.listenreader.service.ports.CleanResult
import com.cnife.listenreader.testing.FakeSettingsStore
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LlmScriptCleanerTest {

    private lateinit var server: MockWebServer
    private lateinit var settingsStore: FakeSettingsStore
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        settingsStore = FakeSettingsStore(AppSettings())
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    @Test
    fun `parses fenced json into segments and sends the prompt`() = runBlocking {
        configure()
        val cleaner = newCleaner()
        server.enqueue(
            jsonResponse(
                completionBody(
                    """
                    ```json
                    {"segments":[{"text":"文章标题","kind":"heading"},{"text":"第一段。","kind":"paragraph"},{"text":"引用。","kind":"quote"}]}
                    ```
                    """.trimIndent(),
                ),
            ),
        )

        val result = cleaner.clean("文章标题", "第一段。\n\n> 引用。")

        assertTrue(result is CleanResult.Success)
        val segments = (result as CleanResult.Success).segments
        assertEquals(
            listOf(
                "文章标题" to SegmentKind.HEADING,
                "第一段。" to SegmentKind.PARAGRAPH,
                "引用。" to SegmentKind.QUOTE,
            ),
            segments.map { it.text to it.kind },
        )

        val request = server.takeRequest()
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("test-model", body["model"]!!.jsonPrimitive.content)
        val messages = body["messages"]!!.jsonArray
        val systemPrompt = messages[0].jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(systemPrompt.contains("不增加"))
        assertTrue(systemPrompt.contains("JSON"))
        val userPrompt = messages[1].jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(userPrompt.contains("文章标题"))
        assertTrue(userPrompt.contains("第一段。"))
    }

    @Test
    fun `retries once when json is invalid and succeeds on the second attempt`() = runBlocking {
        configure()
        val cleaner = newCleaner()
        server.enqueue(jsonResponse(completionBody("抱歉，我无法输出 JSON。")))
        server.enqueue(jsonResponse(completionBody("""{"segments":[{"text":"重试成功。","kind":"paragraph"}]}""")))

        val result = cleaner.clean("标题", "正文内容。")

        assertTrue(result is CleanResult.Success)
        assertEquals(2, server.requestCount)
        assertEquals("重试成功。", (result as CleanResult.Success).segments.last().text)
    }

    @Test
    fun `missing kind retries once then fails with readable reason`() = runBlocking {
        configure()
        val cleaner = newCleaner()
        val broken = completionBody("""{"segments":[{"text":"没有 kind 的段落"}]}""")
        server.enqueue(jsonResponse(broken))
        server.enqueue(jsonResponse(broken))

        val result = cleaner.clean("标题", "正文内容。")

        assertTrue(result is CleanResult.Failure)
        val failure = result as CleanResult.Failure
        assertTrue(failure.retryable)
        assertTrue(failure.message.contains("JSON"))
        assertEquals("恰好重试一次", 2, server.requestCount)
    }

    @Test
    fun `non json prose fails after exactly one retry`() = runBlocking {
        configure()
        val cleaner = newCleaner()
        repeat(2) { server.enqueue(jsonResponse(completionBody("这不是 JSON，只是一段解释。"))) }

        val result = cleaner.clean("标题", "正文内容。")

        assertTrue(result is CleanResult.Failure)
        assertTrue((result as CleanResult.Failure).retryable)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `splits long markdown into chunks within the 8000 character limit`() = runBlocking {
        configure()
        val cleaner = newCleaner()
        val chunkLengths = Collections.synchronizedList(mutableListOf<Int>())
        var responseIndex = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                val userPrompt = body["messages"]!!.jsonArray[1].jsonObject["content"]!!
                    .jsonPrimitive.content
                val chunk = userPrompt.substringAfter("正文：\n")
                chunkLengths += chunk.length
                responseIndex++
                val reply = completionBody(
                    """{"segments":[{"text":"块$responseIndex","kind":"paragraph"}]}""",
                )
                return jsonResponse(reply)
            }
        }

        val paragraphs = (1..400).map { index ->
            "第${index}段。" + "这是一句足够长、需要被清洗与分段的正文内容。".repeat(2)
        }
        val markdown = paragraphs.joinToString("\n\n")

        val result = cleaner.clean("长文章", markdown)

        assertTrue(result is CleanResult.Success)
        assertTrue("应切分为多块", chunkLengths.size >= 2)
        chunkLengths.forEach { length ->
            assertTrue("块长度 $length 应不超过 8000", length <= 8000)
        }
        val segments = (result as CleanResult.Success).segments
        assertEquals("长文章", segments.first().text)
        assertEquals(SegmentKind.HEADING, segments.first().kind)
        assertEquals(
            (1..chunkLengths.size).map { "块$it" },
            segments.drop(1).map { it.text },
        )
    }

    @Test
    fun `prepends the title heading when the model omits it`() = runBlocking {
        configure()
        val cleaner = newCleaner()
        server.enqueue(jsonResponse(completionBody("""{"segments":[{"text":"正文。","kind":"paragraph"}]}""")))

        val result = cleaner.clean("标题", "正文。")

        val segments = (result as CleanResult.Success).segments
        assertEquals("标题", segments.first().text)
        assertEquals(SegmentKind.HEADING, segments.first().kind)
        assertEquals(2, segments.size)
    }

    @Test
    fun `without a key it fails fast and never calls the network`() = runBlocking {
        val cleaner = newCleaner()

        assertFalse(cleaner.isConfigured())
        val result = cleaner.clean("标题", "正文内容。")

        assertTrue(result is CleanResult.Failure)
        val failure = result as CleanResult.Failure
        assertFalse(failure.retryable)
        assertTrue(failure.message.contains("未配置"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `http auth failure surfaces a readable non retryable message`() = runBlocking {
        configure()
        val cleaner = newCleaner()
        server.enqueue(MockResponse().setResponseCode(401))

        val result = cleaner.clean("标题", "正文内容。")

        assertTrue(result is CleanResult.Failure)
        val failure = result as CleanResult.Failure
        assertFalse(failure.retryable)
        assertTrue(failure.message.contains("Key"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `isConfigured follows the saved key`() = runBlocking {
        val cleaner = newCleaner()
        assertFalse(cleaner.isConfigured())

        configure()
        assertTrue(cleaner.isConfigured())

        settingsStore.setLlmConfig("http://localhost/v1", "test-model", null)
        assertFalse(cleaner.isConfigured())
    }

    private suspend fun configure() {
        settingsStore.setLlmConfig(
            endpoint = server.url("/api/v1").toString(),
            model = "test-model",
            key = "sk-test-secret",
        )
    }

    private fun newCleaner(): LlmScriptCleaner =
        LlmScriptCleaner(settingsStore = settingsStore, scope = scope)

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun completionBody(content: String): String = buildJsonObject {
        putJsonArray("choices") {
            addJsonObject {
                putJsonObject("message") {
                    put("role", "assistant")
                    put("content", content)
                }
            }
        }
    }.toString()
}
