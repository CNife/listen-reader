package com.cnife.listenreader.l2

import com.cnife.listenreader.core.ErrorKind
import com.cnife.listenreader.core.UiError
import com.cnife.listenreader.model.LlmDefaults
import com.cnife.listenreader.service.ports.ConnectionTestResult
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class LlmConfig(
    val endpoint: String,
    val model: String,
    val apiKey: String,
)

data class ChatMessage(val role: String, val content: String)

sealed interface ChatCompletion {
    data class Success(val content: String) : ChatCompletion
    data class Failure(val error: UiError) : ChatCompletion
}

class OpenAiCompatibleClient(
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val logger: (String) -> Unit = {},
) {

    suspend fun complete(
        config: LlmConfig,
        messages: List<ChatMessage>,
        maxTokens: Int = DEFAULT_MAX_TOKENS,
        temperature: Double = 0.0,
    ): ChatCompletion {
        val url = chatCompletionsUrl(config.endpoint)
        val payload = buildJsonObject {
            put("model", config.model)
            put("messages", buildJsonArray {
                messages.forEach { message ->
                    add(buildJsonObject {
                        put("role", message.role)
                        put("content", message.content)
                    })
                }
            })
            put("temperature", temperature)
            put("max_tokens", maxTokens)
            put("stream", false)
        }
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return withContext(Dispatchers.IO) {
            try {
                httpClient.newCall(request).execute().use { response ->
                    logger("L2 POST /chat/completions -> ${response.code}")
                    if (!response.isSuccessful) {
                        return@withContext ChatCompletion.Failure(mapHttpFailure(response.code))
                    }
                    val body = response.body?.string().orEmpty()
                    val content = extractContent(body)
                    if (content == null) {
                        logger("L2 POST /chat/completions -> 响应缺少 choices")
                        ChatCompletion.Failure(
                            UiError("LLM 返回内容异常，请稍后重试", ErrorKind.PARSE, retryable = true),
                        )
                    } else {
                        ChatCompletion.Success(content)
                    }
                }
            } catch (io: IOException) {
                logger("L2 POST /chat/completions 失败: ${io.javaClass.simpleName}")
                ChatCompletion.Failure(
                    UiError("网络连接失败，请检查网络后重试", ErrorKind.NETWORK, retryable = true),
                )
            }
        }
    }

    suspend fun testConnection(config: LlmConfig): ConnectionTestResult {
        val messages = listOf(
            ChatMessage("system", "这是一次连接测试。"),
            ChatMessage("user", "请只回复 OK。"),
        )
        return when (val outcome = complete(config, messages, maxTokens = TEST_MAX_TOKENS)) {
            is ChatCompletion.Success -> ConnectionTestResult(true, "LLM 连接正常")
            is ChatCompletion.Failure -> ConnectionTestResult(false, outcome.error.message)
        }
    }

    companion object {
        private const val DEFAULT_MAX_TOKENS = 4096
        private const val TEST_MAX_TOKENS = 16
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .build()

        fun chatCompletionsUrl(endpoint: String): String {
            val base = endpoint.trim().trimEnd('/').ifBlank { LlmDefaults.ENDPOINT }
            return "$base/chat/completions"
        }

        private fun extractContent(body: String): String? = runCatching {
            val choices = Json.parseToJsonElement(body).jsonObject["choices"]?.jsonArray
            val message = (choices?.firstOrNull() as? JsonObject)?.get("message")?.jsonObject
            message?.get("content")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        }.getOrNull()

        private fun mapHttpFailure(code: Int): UiError = when {
            code == 401 -> UiError("LLM Key 无效或已过期，请到设置检查", ErrorKind.AUTH)
            code == 402 -> UiError("LLM 账户余额不足，请充值后重试", ErrorKind.BALANCE)
            code == 403 -> UiError("当前 Key 无权使用该模型或端点", ErrorKind.PERMISSION)
            code == 429 -> UiError("LLM 请求过于频繁，请稍后重试", ErrorKind.RATE_LIMIT, retryable = true)
            code in 500..599 -> UiError(
                "LLM 服务暂时不可用（HTTP $code），请稍后重试",
                ErrorKind.SERVER,
                retryable = true,
            )
            else -> UiError("LLM 请求失败（HTTP $code），请检查端点与模型", ErrorKind.UNKNOWN)
        }
    }
}
