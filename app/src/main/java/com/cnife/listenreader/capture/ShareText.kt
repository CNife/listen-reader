package com.cnife.listenreader.capture

import com.cnife.listenreader.model.SourceLabels
import java.net.URI

object ShareText {

    private val URL_PATTERN = Regex(
        """https?://[^\s<>"'`（）()\[\]{}，。；、]+""",
        RegexOption.IGNORE_CASE,
    )

    private val TRAILING = charArrayOf(
        '.', ',', ';', ':', '!', '?', '。', '，', '；', '：', '！', '？',
        ')', ']', '}', '）', '】', '》', '"', '\'', '>',
    )

    fun firstUrl(text: String): String? {
        for (match in URL_PATTERN.findAll(text)) {
            val candidate = match.value.trimEnd(*TRAILING)
            if (normalizeUrl(candidate) != null) return candidate
        }
        return null
    }

    fun normalizeUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.host.isNullOrBlank()) return null
        return trimmed
    }

    fun sourceLabel(url: String): String = SourceLabels.fromUrl(url)
}
