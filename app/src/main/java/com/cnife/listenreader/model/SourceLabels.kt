package com.cnife.listenreader.model

import java.net.URI

/**
 * 来源展示名：只对常见站点做友好映射，其余保留去掉 `www.` 的 host。
 * 数据层、播放器和采集预览共用，避免同一篇内容在不同页面显示不同来源。
 */
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
