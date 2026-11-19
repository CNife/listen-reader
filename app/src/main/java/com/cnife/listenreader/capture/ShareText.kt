package com.cnife.listenreader.capture

import java.net.URI

/** 分享内容的 URL / 文本判别与 URL 校验（纯函数，便于测试）。 */
object ShareText {

    private val URL_PATTERN = Regex(
        """https?://[^\s<>"'`（）()\[\]{}，。；、]+""",
        RegexOption.IGNORE_CASE,
    )

    private val TRAILING = charArrayOf(
        '.', ',', ';', ':', '!', '?', '。', '，', '；', '：', '！', '？',
        ')', ']', '}', '）', '】', '》', '"', '\'', '>',
    )

    /** 返回文本中第一个可用的 http(s) URL；去掉中文/英文句末标点。 */
    fun firstUrl(text: String): String? {
        for (match in URL_PATTERN.findAll(text)) {
            val candidate = match.value.trimEnd(*TRAILING)
            if (normalizeUrl(candidate) != null) return candidate
        }
        return null
    }

    /** 只接受绝对 http(s) URL；返回原字符串（已 trim）。 */
    fun normalizeUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.host.isNullOrBlank()) return null
        return trimmed
    }

    /** 来源展示名：host 去掉 `www.`。 */
    fun sourceLabel(url: String): String {
        val host = runCatching { URI(url.trim()).host }.getOrNull().orEmpty()
        return host.removePrefix("www.").ifBlank { "网页" }
    }
}
