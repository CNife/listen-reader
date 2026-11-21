package com.cnife.listenreader.l2

import com.cnife.listenreader.model.SegmentDraft
import com.cnife.listenreader.model.SegmentKind
import com.cnife.listenreader.service.ports.CleanResult
import com.cnife.listenreader.service.ports.ConnectionTestResult
import com.cnife.listenreader.service.ports.ScriptCleaner
import com.cnife.listenreader.service.ports.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

class LlmScriptCleaner(
    private val settingsStore: SettingsStore,
    private val client: OpenAiCompatibleClient = OpenAiCompatibleClient(),
    scope: CoroutineScope,
    private val logger: (String) -> Unit = {},
) : ScriptCleaner {

    @Volatile
    private var cachedConfig: LlmConfig? = null

    init {
        scope.launch {
            settingsStore.settings.collect { appSettings ->
                cachedConfig = readConfig(appSettings.llmEndpoint, appSettings.llmModel, appSettings.llmKeyConfigured)
            }
        }
    }

    override fun isConfigured(): Boolean = cachedConfig != null

    override suspend fun clean(title: String, markdown: String): CleanResult {
        val config = currentConfig()
            ?: return CleanResult.Failure(NOT_CONFIGURED_MESSAGE, retryable = false)
        val normalizedTitle = title.trim()
        val body = markdown.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (body.isEmpty()) {
            return CleanResult.Failure("正文为空，无法进行 L2 清洗", retryable = false)
        }

        val chunks = ScriptChunker.chunk(body)
        if (chunks.isEmpty()) {
            return CleanResult.Failure("正文为空，无法进行 L2 清洗", retryable = false)
        }

        val segments = mutableListOf<SegmentDraft>()
        chunks.forEachIndexed { index, chunk ->
            when (val outcome = requestChunk(config, normalizedTitle, chunk, index, chunks.size)) {
                is CleanResult.Success -> segments += outcome.segments
                is CleanResult.Failure -> return outcome
            }
        }

        if (segments.isEmpty()) {
            return CleanResult.Failure("LLM 没有返回任何分段，请重试", retryable = true)
        }
        if (normalizedTitle.isNotEmpty()) {
            val first = segments.first()
            val alreadyTitled = first.kind == SegmentKind.HEADING && first.text.trim() == normalizedTitle
            if (!alreadyTitled) {
                segments.add(0, SegmentDraft(normalizedTitle, SegmentKind.HEADING))
            }
        }
        return CleanResult.Success(segments)
    }

    override suspend fun testConnection(): ConnectionTestResult {
        val config = currentConfig()
            ?: return ConnectionTestResult(false, NOT_CONFIGURED_MESSAGE)
        return client.testConnection(config)
    }

    private suspend fun requestChunk(
        config: LlmConfig,
        title: String,
        chunk: String,
        index: Int,
        total: Int,
    ): CleanResult {
        val messages = listOf(
            ChatMessage("system", SYSTEM_PROMPT),
            ChatMessage("user", buildUserMessage(title, chunk, index, total)),
        )
        var lastFailure: CleanResult.Failure? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            when (val outcome = client.complete(config, messages)) {
                is ChatCompletion.Failure -> return CleanResult.Failure(
                    outcome.error.message,
                    retryable = outcome.error.retryable,
                )
                is ChatCompletion.Success -> {
                    val parsed = SegmentJsonParser.parse(outcome.content)
                    if (parsed != null) {
                        return CleanResult.Success(parsed)
                    }
                    logger("L2 返回内容不是有效分段 JSON（第 ${attempt + 1} 次）")
                    lastFailure = CleanResult.Failure(
                        "LLM 返回的清洗结果不是有效 JSON，已重试一次",
                        retryable = true,
                    )
                }
            }
        }
        return lastFailure
            ?: CleanResult.Failure("LLM 返回的清洗结果无法解析，请重试", retryable = true)
    }

    private suspend fun currentConfig(): LlmConfig? {
        val appSettings = settingsStore.settings.first()
        return readConfig(appSettings.llmEndpoint, appSettings.llmModel, appSettings.llmKeyConfigured)
            .also { cachedConfig = it }
    }

    private suspend fun readConfig(endpoint: String, model: String, keyConfigured: Boolean): LlmConfig? {
        val key = if (keyConfigured) settingsStore.llmKey() else null
        if (key.isNullOrBlank()) return null
        return LlmConfig(
            endpoint = endpoint.trim().ifBlank { com.cnife.listenreader.model.LlmDefaults.ENDPOINT },
            model = model.trim().ifBlank { com.cnife.listenreader.model.LlmDefaults.MODEL },
            apiKey = key.trim(),
        )
    }

    private fun buildUserMessage(title: String, chunk: String, index: Int, total: Int): String = buildString {
        append("标题：").append(title.ifBlank { "（无标题）" }).append('\n')
        if (total > 1) {
            append("本块是正文的第 ").append(index + 1).append(" / ").append(total).append(" 块，只处理本块内容。\n")
        }
        append("正文：\n").append(chunk)
    }

    private companion object {
        const val MAX_ATTEMPTS = 2
        const val NOT_CONFIGURED_MESSAGE = "尚未配置 LLM Key，请在设置中填写端点、模型与 Key"

        val SYSTEM_PROMPT = """
            你是中文朗读稿编辑器。把用户给出的网页正文整理成适合语音朗读的分段脚本。
            必须遵守：
            1. 只整理，不改写：不增加、不删除任何事实，不摘要、不扩写、不评论、不补充背景。
            2. 删除与正文无关的内容：导航、菜单、页脚、版权声明、关注/点赞/分享引导、广告推广、相关阅读推荐。
            3. 保留原有的标题层级：文章标题和各级小标题用 kind=heading，普通段落用 kind=paragraph，引用用 kind=quote。
            4. 每个 segment 是一到三句自然的中文，避免长到无法朗读，也不要把不同段落并成一段。
            5. 只输出严格 JSON，不要 Markdown 代码块，不要任何解释文字。
            输出格式：
            {"segments":[{"text":"...","kind":"heading|paragraph|quote"}]}
        """.trimIndent()
    }
}

internal object SegmentJsonParser {

    fun parse(raw: String): List<SegmentDraft>? {
        val jsonText = extractJsonObject(raw) ?: return null
        val root = runCatching { Json.parseToJsonElement(jsonText).jsonObject }.getOrNull() ?: return null
        val array = root["segments"]?.jsonArray ?: return null
        if (array.isEmpty()) return null
        val drafts = array.map { element ->
            val obj = element as? JsonObject ?: return null
            val text = (obj["text"] as? JsonPrimitive)?.contentOrNull?.trim() ?: return null
            val kindText = (obj["kind"] as? JsonPrimitive)?.contentOrNull?.trim() ?: return null
            val kind = kindOf(kindText) ?: return null
            if (text.isEmpty()) return null
            SegmentDraft(text, kind)
        }
        return drafts
    }

    private fun kindOf(value: String): SegmentKind? = when (value.lowercase()) {
        "heading", "title" -> SegmentKind.HEADING
        "paragraph", "text", "body" -> SegmentKind.PARAGRAPH
        "quote", "blockquote" -> SegmentKind.QUOTE
        else -> null
    }

    private fun extractJsonObject(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return trimmed.substring(start, end + 1)
    }
}
