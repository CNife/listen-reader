package com.cnife.listenreader.l2

import com.cnife.listenreader.core.ErrorKind
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpenAiCompatibleClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `sends chat request with bearer token model and messages`() = runBlocking {
        server.enqueue(jsonResponse(completionBody("ok")))

        val client = OpenAiCompatibleClient()
        val outcome = client.complete(
            config(),
            listOf(
                ChatMessage("system", "系统提示"),
                ChatMessage("user", "标题：文章\n\n正文：第一段。"),
            ),
        )

        assertTrue(outcome is ChatCompletion.Success)
        assertEquals("ok", (outcome as ChatCompletion.Success).content)

        val recorded = server.takeRequest()
        assertEquals("/api/v1/chat/completions", recorded.path)
        assertEquals("Bearer sk-test-secret", recorded.getHeader("Authorization"))
        assertTrue(recorded.getHeader("Content-Type")!!.startsWith("application/json"))

        val body = kotlinx.serialization.json.Json
            .parseToJsonElement(recorded.body.readUtf8())
            .jsonObject
        assertEquals("openai/gpt-4.1-mini", body["model"]!!.jsonPrimitive.content)
        val messages = body["messages"]!!.jsonArray
        assertEquals(2, messages.size)
        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals(
            "标题：文章\n\n正文：第一段。",
            messages[1].jsonObject["content"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `endpoint trailing slash does not double the separator`() = runBlocking {
        server.enqueue(jsonResponse(completionBody("ok")))
        val endpoint = server.url("/api/v1/").toString()

        val outcome = OpenAiCompatibleClient().complete(
            config(endpoint = endpoint),
            listOf(ChatMessage("user", "hi")),
        )

        assertTrue(outcome is ChatCompletion.Success)
        assertEquals("/api/v1/chat/completions", server.takeRequest().path)
    }

    @Test
    fun `http errors map to readable kinds`() = runBlocking {
        val cases = listOf(
            401 to ErrorKind.AUTH,
            402 to ErrorKind.BALANCE,
            403 to ErrorKind.PERMISSION,
            429 to ErrorKind.RATE_LIMIT,
            500 to ErrorKind.SERVER,
            503 to ErrorKind.SERVER,
        )
        val client = OpenAiCompatibleClient()
        for ((code, kind) in cases) {
            server.enqueue(MockResponse().setResponseCode(code).setBody("""{"error":"upstream"}"""))

            val outcome = client.complete(config(), listOf(ChatMessage("user", "hi")))

            assertTrue("HTTP $code 应失败", outcome is ChatCompletion.Failure)
            val error = (outcome as ChatCompletion.Failure).error
            assertEquals("HTTP $code 的错误类型", kind, error.kind)
            assertTrue("HTTP $code 的提示应可读", error.message.isNotBlank())
            if (code == 429 || code >= 500) {
                assertTrue("HTTP $code 应可重试", error.retryable)
            } else {
                assertFalse("HTTP $code 不应重试", error.retryable)
            }
        }
    }

    @Test
    fun `connection failure maps to network error`() = runBlocking {
        val client = OpenAiCompatibleClient(
            httpClient = OkHttpClient.Builder()
                .connectTimeout(500, TimeUnit.MILLISECONDS)
                .readTimeout(500, TimeUnit.MILLISECONDS)
                .build(),
        )

        val outcome = client.complete(
            LlmConfig(endpoint = "http://127.0.0.1:1/v1", model = "m", apiKey = "sk-test-secret"),
            listOf(ChatMessage("user", "hi")),
        )

        assertTrue(outcome is ChatCompletion.Failure)
        val error = (outcome as ChatCompletion.Failure).error
        assertEquals(ErrorKind.NETWORK, error.kind)
        assertTrue(error.retryable)
        assertFalse(error.message.contains("sk-test-secret"))
    }

    @Test
    fun `non chat completion body maps to parse failure`() = runBlocking {
        server.enqueue(jsonResponse("""{"unexpected":true}"""))

        val outcome = OpenAiCompatibleClient().complete(config(), listOf(ChatMessage("user", "hi")))

        assertTrue(outcome is ChatCompletion.Failure)
        assertEquals(ErrorKind.PARSE, (outcome as ChatCompletion.Failure).error.kind)
    }

    @Test
    fun `logs never contain api key or prompt content`() = runBlocking {
        val lines = mutableListOf<String>()
        val client = OpenAiCompatibleClient(logger = { lines += it })
        val secretContent = "这是一段不能进日志的正文内容"

        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"boom"}"""))
        client.complete(config(), listOf(ChatMessage("user", secretContent)))

        server.enqueue(jsonResponse(completionBody("ok")))
        client.complete(config(), listOf(ChatMessage("user", secretContent)))

        assertTrue(lines.isNotEmpty())
        lines.forEach { line ->
            assertFalse("日志泄露了 key: $line", line.contains("sk-test-secret"))
            assertFalse("日志泄露了正文: $line", line.contains(secretContent))
        }
    }

    @Test
    fun `test connection success and failure are readable`() = runBlocking {
        val client = OpenAiCompatibleClient()

        server.enqueue(jsonResponse(completionBody("OK")))
        val ok = client.testConnection(config())
        assertTrue(ok.ok)
        assertTrue(ok.message.isNotBlank())

        server.enqueue(MockResponse().setResponseCode(401))
        val failed = client.testConnection(config())
        assertFalse(failed.ok)
        assertTrue(failed.message.contains("Key"))
        assertFalse(failed.message.contains("sk-test-secret"))
    }

    private fun config(
        endpoint: String = server.url("/api/v1").toString(),
        key: String = "sk-test-secret",
    ) = LlmConfig(
        endpoint = endpoint,
        model = "openai/gpt-4.1-mini",
        apiKey = key,
    )

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
