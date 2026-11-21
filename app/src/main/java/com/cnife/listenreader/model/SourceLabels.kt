package com.cnife.listenreader.model

import java.net.URI

object SourceLabels {

    const val TEXT = "粘贴文本"

    fun of(kind: SourceKind, url: String?): String =
        if (kind == SourceKind.TEXT) TEXT else fromUrl(url.orEmpty())

    fun fromUrl(url: String): String {
        val host = runCatching { URI(url.trim()).host }.getOrNull()
        val normalized = host?.removePrefix("www.")?.takeIf { it.isNotBlank() } ?: return "网页"
        return when (normalized.lowercase()) {
            "mp.weixin.qq.com" -> "微信公众号"
            "sspai.com" -> "少数派"
            "zhihu.com" -> "知乎"
            "medium.com" -> "Medium"
            "juejin.cn" -> "稀土掘金"
            "github.com" -> "GitHub"
            else -> normalized
        }
    }
}
