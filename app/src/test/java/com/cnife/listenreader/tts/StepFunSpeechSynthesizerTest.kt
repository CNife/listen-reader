package com.cnife.listenreader.tts

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cnife.listenreader.core.ErrorKind
import com.cnife.listenreader.testing.FakeSettingsStore
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class StepFunSpeechSynthesizerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var settings: FakeSettingsStore
    private lateinit var cacheDir: File
    private val logs = mutableListOf<String>()

    @Before
    fun setUp() = runBlocking {
        server = MockWebServer()
        server.start()
        settings = FakeSettingsStore()
        settings.setStepFunKey(SECRET_KEY)
        cacheDir = tempFolder.newFolder("cache")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `synthesize posts the documented StepFun contract and writes audio`() = runBlocking {
        val audio = byteArrayOf(0x49, 0x44, 0x33, 0x04, 0x00, 0x00)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "audio/mpeg")
                .setBody(okio.Buffer().write(audio)),
        )
        val destination = File(cacheDir, "segments/0.mp3")

        val duration = synthesizer().synthesize(
            text = "第一句要朗读的文字。",
            model = "stepaudio-3-tts",
            voice = "wenrounvsheng",
            destination = destination,
        )

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/audio/speech", request.path)
        assertEquals("Bearer $SECRET_KEY", request.getHeader("Authorization"))
        assertEquals("audio/mpeg", request.getHeader("Accept"))
        assertTrue(request.getHeader("Content-Type").orEmpty().startsWith("application/json"))

        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("stepaudio-3-tts", body.getValue("model").jsonPrimitive.content)
        assertEquals("第一句要朗读的文字。", body.getValue("input").jsonPrimitive.content)
        assertEquals("wenrounvsheng", body.getValue("voice").jsonPrimitive.content)
        assertEquals("mp3", body.getValue("response_format").jsonPrimitive.content)
        assertEquals("24000", body.getValue("sample_rate").jsonPrimitive.content)
        assertEquals("enhanced", body.getValue("text_normalization").jsonPrimitive.content)
        assertEquals("1.0", body.getValue("speed").jsonPrimitive.content)
        assertEquals("1.0", body.getValue("volume").jsonPrimitive.content)

        assertTrue(destination.isFile)
        assertTrue(destination.readBytes().contentEquals(audio))
        assertFalse(File(destination.parentFile, "0.mp3.tmp").exists())
        assertEquals(FIXED_DURATION_MS, duration)
    }

    @Test
    fun `input longer than 1000 characters is truncated`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("audio"))
        val destination = File(cacheDir, "long.mp3")

        synthesizer().synthesize(VERY_LONG_TEXT, "stepaudio-3-tts", "wenrounvsheng", destination)

        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(1000, body.getValue("input").jsonPrimitive.content.length)
    }

    @Test
    fun `401 402 403 map to readable errors and are not retried`() = runBlocking {
        val expectations = mapOf(
            401 to ErrorKind.AUTH,
            402 to ErrorKind.BALANCE,
            403 to ErrorKind.PERMISSION,
        )
        expectations.forEach { (code, kind) ->
            val before = server.requestCount
            server.enqueue(MockResponse().setResponseCode(code))
            val error = synthesizeExpectingError()
            assertEquals(kind, error.kind)
            assertEquals(1, server.requestCount - before)
            server.takeRequest()
        }
    }

    @Test
    fun `429 and 5xx retry twice with exponential backoff`() = runBlocking {
        val beforeRetry = server.requestCount
        repeat(3) { server.enqueue(MockResponse().setResponseCode(429)) }
        val delays = mutableListOf<Long>()
        val error = synthesizeExpectingError(delays = delays)

        assertEquals(ErrorKind.RATE_LIMIT, error.kind)
        assertEquals(3, server.requestCount - beforeRetry)
        assertEquals(listOf(1_000L, 2_000L), delays)

        repeat(3) { server.takeRequest() }

        val beforeServerError = server.requestCount
        repeat(3) { server.enqueue(MockResponse().setResponseCode(503)) }
        val serverError = synthesizeExpectingError()
        assertEquals(ErrorKind.SERVER, serverError.kind)
        assertEquals(3, server.requestCount - beforeServerError)
    }

    @Test
    fun `other 4xx fails immediately without retry`() = runBlocking {
        val before = server.requestCount
        server.enqueue(MockResponse().setResponseCode(404))
        val error = synthesizeExpectingError()
        assertEquals(ErrorKind.UNKNOWN, error.kind)
        assertEquals(1, server.requestCount - before)
    }

    @Test
    fun `connection failures map to network and retry twice`() = runBlocking {
        repeat(3) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)) }
        val client = OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .connectTimeout(20, TimeUnit.MILLISECONDS)
            .readTimeout(20, TimeUnit.MILLISECONDS)
            .build()
        val error = synthesizeExpectingError(client = client)
        assertEquals(ErrorKind.NETWORK, error.kind)
        assertTrue(server.requestCount <= 3)
    }

    @Test
    fun `missing key fails with NO_KEY before any request`() = runBlocking {
        val emptySettings = FakeSettingsStore()
        val error = runCatching {
            synthesizer(settingsStore = emptySettings).synthesize(
                "句子",
                "stepaudio-3-tts",
                "wenrounvsheng",
                File(cacheDir, "nokey.mp3"),
            )
        }.exceptionOrNull() as SpeechException

        assertEquals(ErrorKind.NO_KEY, error.kind)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `logs never contain the key or synthesized text`() = runBlocking {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(500)) }
        val text = "这是不该出现在日志里的正文。"
        runCatching {
            synthesizer().synthesize(text, "stepaudio-3-tts", "wenrounvsheng", File(cacheDir, "x.mp3"))
        }

        assertTrue(logs.isNotEmpty())
        logs.forEach { line ->
            assertFalse(line.contains(SECRET_KEY))
            assertFalse(line.contains(text))
        }
    }

    @Test
    fun `testConnection synthesizes a fixed phrase and reports readable success`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("audio"))

        val result = synthesizer().testConnection("step-tts-mini", "linjiajiejie")

        assertTrue(result.ok)
        assertTrue(result.message.contains("连接成功"))
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("你好，这是听我读的音色试听。", body.getValue("input").jsonPrimitive.content)
        assertEquals("step-tts-mini", body.getValue("model").jsonPrimitive.content)
        assertEquals("linjiajiejie", body.getValue("voice").jsonPrimitive.content)
    }

    @Test
    fun `testConnection reports failure with readable message`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(402))
        val result = synthesizer().testConnection("stepaudio-3-tts", "wenrounvsheng")
        assertFalse(result.ok)
        assertTrue(result.message.contains("余额"))
    }

    private fun synthesizer(
        settingsStore: com.cnife.listenreader.service.ports.SettingsStore = settings,
        client: OkHttpClient? = null,
        delays: MutableList<Long> = mutableListOf(),
    ): StepFunSpeechSynthesizer = StepFunSpeechSynthesizer(
        settingsStore = settingsStore,
        cacheDir = cacheDir,
        baseUrl = server.url("/v1").toString().trimEnd('/'),
        httpClient = client ?: OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .readTimeout(30, TimeUnit.SECONDS)
            .build(),
        retryDelaysMs = listOf(1_000L, 2_000L),
        sleep = { delays += it },
        durationReader = { FIXED_DURATION_MS },
        log = { logs += it },
    )

    private suspend fun synthesizeExpectingError(
        delays: MutableList<Long> = mutableListOf(),
        client: OkHttpClient? = null,
    ): SpeechException = runCatching {
        synthesizer(client = client, delays = delays).synthesize(
            "第二句。",
            "stepaudio-3-tts",
            "wenrounvsheng",
            File(cacheDir, "error.mp3"),
        )
    }.exceptionOrNull() as SpeechException

    private companion object {
        const val SECRET_KEY = "sk-test-secret-key"
        const val FIXED_DURATION_MS = 1_234L
        val VERY_LONG_TEXT = "长".repeat(1500)
    }
}
