package com.cnife.listenreader.tts

import android.util.Log
import com.cnife.listenreader.core.ErrorKind
import com.cnife.listenreader.model.QwenDefaults
import com.cnife.listenreader.service.ports.ConnectionTestResult
import com.cnife.listenreader.service.ports.SettingsStore
import com.cnife.listenreader.service.ports.SpeechSynthesizer
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 千问 AI 平台官方非流式 TTS：`POST {base}/services/audio/tts/SpeechSynthesizer`。
 *
 * 官方非流式接口先返回 JSON（`output.audio.url` 或 `output.audio.data`），本实现解析响应后：
 * - 若包含非空 `output.audio.data`（Base64），直接解码为音频字节；
 * - 否则请求 `output.audio.url` 下载音频字节（非本地回环的 `http://` 自动升级为 `https://`，兼容 Android release 禁明文流量策略）；
 * 最后先写 `<destination>.tmp` 再原子改名落盘。
 *
 * 429/5xx/超时/IO 重试 2 次（指数退避），4xx 不重试；日志绝不记录 API Key 或正文。
 */
class QwenSpeechSynthesizer(
    private val settingsStore: SettingsStore,
    private val cacheDir: File,
    @Volatile var baseUrl: String = QwenDefaults.BASE_URL,
    private val httpClient: OkHttpClient = defaultTtsHttpClient(),
    private val retryDelaysMs: List<Long> = DEFAULT_TTS_RETRY_DELAYS,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val durationReader: (File) -> Long = ::readDurationMs,
    private val log: (String) -> Unit = { message -> Log.i(TAG, message) },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SpeechSynthesizer {

    override suspend fun synthesize(
        text: String,
        model: String,
        voice: String,
        destination: File,
    ): Long = withContext(ioDispatcher) {
        val key = settingsStore.qwenKey()?.takeIf { it.isNotBlank() }
            ?: throw SpeechException(
                ErrorKind.NO_KEY,
                "尚未配置千问 Key，请到设置填写",
            )
        val input = text.trim()
        if (input.isEmpty()) {
            throw SpeechException(ErrorKind.UNKNOWN, "没有可合成的文本")
        }
        val clipped = if (input.length > MAX_INPUT_CHARS) input.take(MAX_INPUT_CHARS) else input
        val body = buildJsonObject {
            put("model", model)
            put(
                "input",
                buildJsonObject {
                    put("text", clipped)
                    put("voice", voice)
                    put("format", "mp3")
                    put("sample_rate", QwenDefaults.SAMPLE_RATE)
                    put("rate", 1.0)
                    put("volume", 50)
                },
            )
        }.toString()
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/services/audio/tts/SpeechSynthesizer")
            .header("Authorization", "Bearer $key")
            .header("Accept", "application/json, audio/mpeg")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        for (attempt in 1..MAX_ATTEMPTS) {
            try {
                val bytes = executeAndResolveAudioBytes(request)
                if (bytes.isEmpty()) {
                    throw SpeechException(
                        ErrorKind.NETWORK,
                        "千问返回了空音频，请稍后重试",
                        retryable = true,
                    )
                }
                writeAudioAtomically(destination, bytes)
                log("qwen synthesize ok: ${bytes.size} bytes")
                return@withContext durationReader(destination)
            } catch (c: CancellationException) {
                throw c
            } catch (e: SpeechException) {
                if (!e.retryable || attempt >= MAX_ATTEMPTS) throw e
                log("qwen synthesize retry ${attempt + 1} after HTTP/response error")
                sleep(nextBackoffDelay(retryDelaysMs, attempt))
            } catch (e: IOException) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw SpeechException(
                        ErrorKind.NETWORK,
                        "网络连接失败，请检查网络后重试",
                        retryable = true,
                        cause = e,
                    )
                }
                log("qwen synthesize retry ${attempt + 1} after IO error")
                sleep(nextBackoffDelay(retryDelaysMs, attempt))
            }
        }
        throw SpeechException(ErrorKind.NETWORK, "网络连接失败，请检查网络后重试", retryable = true)
    }

    override suspend fun testConnection(model: String, voice: String): ConnectionTestResult {
        val directory = cacheDir.apply { mkdirs() }
        val file = runCatching { File.createTempFile("qwen-test-", ".mp3", directory) }
            .getOrElse {
                return ConnectionTestResult(false, "本地缓存目录不可写")
            }
        return try {
            val durationMs = synthesize(TTS_TEST_PHRASE, model, voice, file)
            val readable = if (durationMs > 0) "（${"%.1f".format(durationMs / 1000.0)} 秒）" else ""
            ConnectionTestResult(true, "连接成功，已合成试听音频$readable")
        } catch (c: CancellationException) {
            throw c
        } catch (e: SpeechException) {
            ConnectionTestResult(false, e.message)
        } catch (e: Throwable) {
            ConnectionTestResult(false, "连接失败，请稍后重试")
        } finally {
            file.delete()
        }
    }

    private fun executeAndResolveAudioBytes(request: Request): ByteArray {
        val (contentType, bodyBytes) = httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw httpError(response.code)
            val ct = response.header("Content-Type").orEmpty()
            val raw = response.body?.bytes() ?: ByteArray(0)
            ct to raw
        }
        if (contentType.startsWith("audio/")) {
            return bodyBytes
        }
        val payloadText = bodyBytes.toString(Charsets.UTF_8)
        val root = runCatching { Json.parseToJsonElement(payloadText).jsonObject }
            .getOrElse {
                throw SpeechException(
                    ErrorKind.NETWORK,
                    "千问响应解析失败，请稍后重试",
                    retryable = true,
                    cause = it,
                )
            }
        val audioObj = root["output"]?.jsonObject?.get("audio")?.jsonObject
            ?: throw SpeechException(
                ErrorKind.NETWORK,
                "千问未返回音频结果，请稍后重试",
                retryable = true,
            )
        val inlineBase64 = audioObj["data"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (inlineBase64.isNotEmpty()) {
            return runCatching { Base64.getDecoder().decode(inlineBase64) }
                .getOrElse {
                    throw SpeechException(
                        ErrorKind.NETWORK,
                        "千问音频数据解码失败，请稍后重试",
                        retryable = true,
                        cause = it,
                    )
                }
        }
        val rawUrl = audioObj["url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (rawUrl.isEmpty()) {
            throw SpeechException(
                ErrorKind.NETWORK,
                "千问返回了空音频地址，请稍后重试",
                retryable = true,
            )
        }
        val downloadUrl = upgradeAudioUrl(rawUrl)
        val downloadRequest = Request.Builder()
            .url(downloadUrl)
            .header("Accept", "audio/mpeg, audio/*, */*")
            .get()
            .build()
        httpClient.newCall(downloadRequest).execute().use { downloadResponse ->
            if (!downloadResponse.isSuccessful) throw httpError(downloadResponse.code)
            return downloadResponse.body?.bytes() ?: ByteArray(0)
        }
    }

    private fun upgradeAudioUrl(url: String): String {
        if (!url.startsWith("http://", ignoreCase = true)) return url
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull()
        if (host in LOOPBACK_HOSTS) return url
        return "https://" + url.substring("http://".length)
    }

    private fun httpError(code: Int): SpeechException {
        log("qwen http $code")
        return when {
            code == 401 -> SpeechException(ErrorKind.AUTH, "千问 Key 无效或已过期，请到设置重新填写")
            code == 402 -> SpeechException(ErrorKind.BALANCE, "千问账户余额不足，请充值后重试")
            code == 403 -> SpeechException(ErrorKind.PERMISSION, "千问 Key 没有访问该模型的权限")
            code == 429 -> SpeechException(ErrorKind.RATE_LIMIT, "千问请求过于频繁，请稍后重试", retryable = true)
            code in 500..599 -> SpeechException(ErrorKind.SERVER, "千问服务暂时不可用，请稍后重试", retryable = true)
            else -> SpeechException(ErrorKind.UNKNOWN, "千问请求失败（HTTP $code）")
        }
    }

    private companion object {
        const val TAG = "QwenTts"
        const val MAX_INPUT_CHARS = 1000
        const val MAX_RETRIES = 2
        const val MAX_ATTEMPTS = MAX_RETRIES + 1
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1", "[::1]", "10.0.2.2")
    }
}
