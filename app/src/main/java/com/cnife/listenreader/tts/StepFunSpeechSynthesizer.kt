package com.cnife.listenreader.tts

import android.media.MediaMetadataRetriever
import android.util.Log
import com.cnife.listenreader.core.ErrorKind
import com.cnife.listenreader.model.StepFunDefaults
import com.cnife.listenreader.service.ports.ConnectionTestResult
import com.cnife.listenreader.service.ports.SettingsStore
import com.cnife.listenreader.service.ports.SpeechSynthesizer
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * StepFun 非流式 TTS：`POST {base}/audio/speech`。
 * 2xx 先写 `<destination>.tmp` 再原子改名；429/5xx/超时/IO 重试 2 次（指数退避），4xx 不重试。
 * 日志只记录状态码与重试次数，绝不写入 key 或正文。
 *
 * [baseUrl] 是可运行期覆写的端点：debug 构建的自验证接收器可把它指向本地 mock 服务，
 * 生产构建保持 [StepFunDefaults.BASE_URL]。
 */
class StepFunSpeechSynthesizer(
    private val settingsStore: SettingsStore,
    private val cacheDir: File,
    @Volatile var baseUrl: String = StepFunDefaults.BASE_URL,
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
        val key = settingsStore.stepFunKey()?.takeIf { it.isNotBlank() }
            ?: throw SpeechException(
                ErrorKind.NO_KEY,
                "尚未配置 StepFun Key，请到设置填写",
            )
        val input = text.trim()
        if (input.isEmpty()) {
            throw SpeechException(ErrorKind.UNKNOWN, "没有可合成的文本")
        }
        val clipped = if (input.length > MAX_INPUT_CHARS) input.take(MAX_INPUT_CHARS) else input
        val body = buildJsonObject {
            put("model", model)
            put("input", clipped)
            put("voice", voice)
            put("response_format", "mp3")
            put("sample_rate", StepFunDefaults.SAMPLE_RATE)
            put("text_normalization", "enhanced")
            put("speed", 1.0)
            put("volume", 1.0)
        }.toString()
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/audio/speech")
            .header("Authorization", "Bearer $key")
            .header("Accept", "audio/mpeg")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        for (attempt in 1..MAX_ATTEMPTS) {
            try {
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw httpError(response.code)
                    val bytes = response.body?.bytes()
                    if (bytes == null || bytes.isEmpty()) {
                        throw SpeechException(
                            ErrorKind.NETWORK,
                            "StepFun 返回了空音频，请稍后重试",
                            retryable = true,
                        )
                    }
                    writeAudioAtomically(destination, bytes)
                    log("synthesize ok: ${bytes.size} bytes")
                    return@withContext durationReader(destination)
                }
            } catch (c: CancellationException) {
                throw c
            } catch (e: SpeechException) {
                if (!e.retryable || attempt >= MAX_ATTEMPTS) throw e
                log("synthesize retry ${attempt + 1} after HTTP/response error")
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
                log("synthesize retry ${attempt + 1} after IO error")
                sleep(nextBackoffDelay(retryDelaysMs, attempt))
            }
        }
        throw SpeechException(ErrorKind.NETWORK, "网络连接失败，请检查网络后重试", retryable = true)
    }

    override suspend fun testConnection(model: String, voice: String): ConnectionTestResult {
        val directory = cacheDir.apply { mkdirs() }
        val file = runCatching { File.createTempFile("stepfun-test-", ".mp3", directory) }
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

    private fun httpError(code: Int): SpeechException {
        log("stepfun http $code")
        return when {
            code == 401 -> SpeechException(ErrorKind.AUTH, "StepFun Key 无效或已过期，请到设置重新填写")
            code == 402 -> SpeechException(ErrorKind.BALANCE, "StepFun 余额不足，请充值后重试")
            code == 403 -> SpeechException(ErrorKind.PERMISSION, "StepFun Key 没有访问该模型的权限")
            code == 429 -> SpeechException(ErrorKind.RATE_LIMIT, "StepFun 请求过于频繁，请稍后重试", retryable = true)
            code in 500..599 -> SpeechException(ErrorKind.SERVER, "StepFun 服务暂时不可用，请稍后重试", retryable = true)
            else -> SpeechException(ErrorKind.UNKNOWN, "StepFun 请求失败（HTTP $code）")
        }
    }

    private companion object {
        const val TAG = "StepFunTts"
        const val MAX_INPUT_CHARS = 1000
        const val MAX_RETRIES = 2
        const val MAX_ATTEMPTS = MAX_RETRIES + 1
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

internal const val TTS_TEST_PHRASE = "你好，这是听我读的音色试听。"
internal const val TTS_TEMP_SUFFIX = ".tmp"
internal val DEFAULT_TTS_RETRY_DELAYS = listOf(1_000L, 2_000L)

internal fun defaultTtsHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .build()

internal fun nextBackoffDelay(retryDelaysMs: List<Long>, attempt: Int): Long =
    retryDelaysMs.getOrElse(attempt - 1) { retryDelaysMs.lastOrNull() ?: 1_000L }

internal fun writeAudioAtomically(destination: File, bytes: ByteArray) {
    destination.parentFile?.mkdirs()
    val temp = File(destination.parentFile, destination.name + TTS_TEMP_SUFFIX)
    temp.writeBytes(bytes)
    if (!temp.renameTo(destination)) {
        temp.delete()
        throw SpeechException(ErrorKind.UNKNOWN, "音频文件写入失败，请重试", retryable = true)
    }
}

/** 读取 MP3 时长；失败返回 0，不影响已成功写入的音频。 */
fun readDurationMs(file: File): Long = runCatching {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(file.absolutePath)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull() ?: 0L
    } finally {
        retriever.release()
    }
}.getOrDefault(0L)
