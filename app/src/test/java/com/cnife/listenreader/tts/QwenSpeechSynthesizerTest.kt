package com.cnife.listenreader.tts

import com.cnife.listenreader.core.ErrorKind
import com.cnife.listenreader.model.QwenDefaults
import com.cnife.listenreader.model.TtsModels
import com.cnife.listenreader.model.Voices
import com.cnife.listenreader.testing.FakeSettingsStore
import java.io.File
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class QwenSpeechSynthesizerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var settings: FakeSettingsStore
    private lateinit var cacheDir: File
    private val logs = mutableListOf<String>()
    private val delays = mutableListOf<Long>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        settings = FakeSettingsStore()
        cacheDir = tempFolder.newFolder("qwen-tts-cache")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun synthesizer(durationMs: Long = 1_800L) = QwenSpeechSynthesizer(
        settingsStore = settings,
        cacheDir = cacheDir,
        baseUrl = server.url("/api/v1").toString().trimEnd('/'),
        retryDelaysMs = listOf(1_000L, 2_000L),
        sleep = { delays += it },
        durationReader = { durationMs },
        log = { logs += it },
    )

    private fun goldenQwenResponseJson(audioUrl: String, data: String = "") = """
        {
          "output": {
            "audio": {
              "data": "$data",
              "id": "audio_golden_fixture_id",
              "expires_at": 1766564488,
              "url": "$audioUrl"
            },
            "finish_reason": "stop"
          },
          "usage": {
            "input_tokens": 3,
            "output_tokens": 9,
            "total_tokens": 12,
            "characters": 10
          },
          "request_id": "req_golden_fixture"
        }
    """.trimIndent()

    @Test
    fun `200 JSON response downloads audio url atomically and sends documented payload`() = runBlocking {
        settings.setQwenKey("sk-qwen-secret-value")
        val mp3 = byteArrayOf(0x49, 0x44, 0x33, 0x03, 0x00, 0x11, 0x22)
        val audioDownloadUrl = server.url("/oss-audio/result.mp3").toString()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(goldenQwenResponseJson(audioUrl = audioDownloadUrl)),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "audio/mpeg")
                .setBody(Buffer().write(mp3)),
        )
        val destination = File(tempFolder.newFolder("audio"), "r1/t1/0.mp3")

        val duration = synthesizer(durationMs = 2_400L).synthesize(
            text = "第一句要朗读的文字。",
            model = TtsModels.QWEN_FLASH,
            voice = Voices.LONGANHUAN,
            destination = destination,
        )

        assertEquals(2_400L, duration)
        assertTrue(destination.isFile)
        assertArrayEquals(mp3, destination.readBytes())
        assertFalse(File(destination.parentFile, "0.mp3.tmp").exists())
        assertEquals(2, server.requestCount)

        val synthRequest = server.takeRequest()
        assertEquals("/api/v1/services/audio/tts/SpeechSynthesizer", synthRequest.path)
        assertEquals("Bearer sk-qwen-secret-value", synthRequest.getHeader("Authorization"))
        val json = Json.parseToJsonElement(synthRequest.body.readUtf8()).jsonObject
        assertEquals(TtsModels.QWEN_FLASH, json["model"]?.jsonPrimitive?.content)
        val input = json["input"]!!.jsonObject
        assertEquals("第一句要朗读的文字。", input["text"]?.jsonPrimitive?.content)
        assertEquals(Voices.LONGANHUAN, input["voice"]?.jsonPrimitive?.content)
        assertEquals("mp3", input["format"]?.jsonPrimitive?.content)
        assertEquals(QwenDefaults.SAMPLE_RATE, input["sample_rate"]?.jsonPrimitive?.int)
        assertEquals(1.0, input["rate"]?.jsonPrimitive?.double ?: 0.0, 0.001)
        assertEquals(50, input["volume"]?.jsonPrimitive?.int)

        val downloadRequest = server.takeRequest()
        assertEquals("/oss-audio/result.mp3", downloadRequest.path)
        assertEquals("GET", downloadRequest.method)
        assertNoSecretInLogs("sk-qwen-secret-value", "第一句要朗读的文字。")
    }

    @Test
    fun `inline base64 audio data is decoded directly without second request`() = runBlocking {
        settings.setQwenKey("sk-qwen")
        val mp3 = byteArrayOf(0x49, 0x44, 0x33, 0x04, 0x55)
        val encoded = Base64.getEncoder().encodeToString(mp3)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(goldenQwenResponseJson(audioUrl = "", data = encoded)),
        )
        val destination = File(tempFolder.newFolder("inline"), "0.mp3")

        val duration = synthesizer().synthesize(
            text = "内联音频测试",
            model = TtsModels.QWEN_FLASH,
            voice = Voices.LONGANFENGYUE,
            destination = destination,
        )

        assertEquals(1_800L, duration)
        assertArrayEquals(mp3, destination.readBytes())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `401 402 and 403 fail immediately without retry`() = runBlocking {
        settings.setQwenKey("sk-qwen-secret")
        val cases = listOf(
            401 to ErrorKind.AUTH,
            402 to ErrorKind.BALANCE,
            403 to ErrorKind.PERMISSION,
        )
        for ((code, expectedKind) in cases) {
            delays.clear()
            val beforeCount = server.requestCount
            server.enqueue(MockResponse().setResponseCode(code))
            val dest = File(tempFolder.newFolder("err-$code"), "0.mp3")

            try {
                synthesizer().synthesize("句子", TtsModels.QWEN_FLASH, Voices.LONGANHUAN, dest)
                fail("HTTP $code should fail")
            } catch (e: SpeechException) {
                assertEquals(expectedKind, e.kind)
                assertFalse(e.retryable)
            }
            assertEquals(1, server.requestCount - beforeCount)
            assertTrue(delays.isEmpty())
            assertFalse(dest.exists())
        }
    }

    @Test
    fun `429 and 500 retry twice with exponential backoff then succeed`() = runBlocking {
        settings.setQwenKey("sk-qwen-secret")
        val mp3 = byteArrayOf(1, 2, 3, 4)
        val audioUrl = server.url("/oss-audio/retry.mp3").toString()
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(goldenQwenResponseJson(audioUrl = audioUrl)),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "audio/mpeg")
                .setBody(Buffer().write(mp3)),
        )
        val dest = File(tempFolder.newFolder("retry"), "0.mp3")

        synthesizer().synthesize("重试句子", TtsModels.QWEN_FLASH, Voices.LONGANHUAN, dest)

        assertEquals(4, server.requestCount)
        assertEquals(listOf(1_000L, 2_000L), delays)
        assertArrayEquals(mp3, dest.readBytes())
    }

    @Test
    fun `missing key fails before touching the network`() = runBlocking {
        val dest = File(tempFolder.newFolder("nokey"), "0.mp3")
        try {
            synthesizer().synthesize("句子", TtsModels.QWEN_FLASH, Voices.LONGANHUAN, dest)
            fail("missing key should throw")
        } catch (e: SpeechException) {
            assertEquals(ErrorKind.NO_KEY, e.kind)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `RoutingSpeechSynthesizer dispatches by model vendor`() = runBlocking {
        settings.setStepFunKey("sk-stepfun")
        settings.setQwenKey("sk-qwen")
        val mp3 = byteArrayOf(0x49, 0x44, 0x33, 0x04)
        val stepSynth = StepFunSpeechSynthesizer(
            settingsStore = settings,
            cacheDir = cacheDir,
            baseUrl = server.url("/v1").toString().trimEnd('/'),
            durationReader = { 1_100L },
            log = { logs += it },
        )
        val qwenSynth = synthesizer(durationMs = 2_200L)
        val router = RoutingSpeechSynthesizer(stepFun = stepSynth, qwen = qwenSynth)

        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(mp3)))
        val stepDest = File(tempFolder.newFolder("route-step"), "0.mp3")
        val stepDuration = router.synthesize("阶跃句子", TtsModels.MINI, Voices.WENROU, stepDest)
        assertEquals(1_100L, stepDuration)
        assertEquals("/v1/audio/speech", server.takeRequest().path)

        val audioUrl = server.url("/oss-audio/routed.mp3").toString()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(goldenQwenResponseJson(audioUrl = audioUrl)),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(mp3)))
        val qwenDest = File(tempFolder.newFolder("route-qwen"), "0.mp3")
        val qwenDuration = router.synthesize("千问句子", TtsModels.QWEN_FLASH, Voices.LONGANHUAN, qwenDest)
        assertEquals(2_200L, qwenDuration)
        assertEquals("/api/v1/services/audio/tts/SpeechSynthesizer", server.takeRequest().path)
        assertEquals("/oss-audio/routed.mp3", server.takeRequest().path)
    }

    private fun assertNoSecretInLogs(vararg secrets: String) {
        val joined = logs.joinToString("\n")
        for (secret in secrets) {
            assertFalse("日志泄露了敏感内容: $secret", joined.contains(secret))
        }
    }
}
