package com.cnife.listenreader.capture.webview

data class L3Candidate(
    val index: Int,
    val tag: String,
    val text: String,
    val visible: Boolean,
    val inForm: Boolean,
    val href: String? = null,
    val type: String? = null,
    val submit: Boolean = false,
    val leaf: Boolean = false,
    val rectWidth: Double = 0.0,
    val rectHeight: Double = 0.0,
) {
    val key: String get() = "$tag|$text"
}

object L3Rules {

    val PHRASES = listOf("展开全文", "展开阅读全文", "阅读全文", "继续阅读", "显示更多", "展开")

    private val BLOCKED_WORDS = listOf(
        "登录", "注册", "付费", "购买", "支付", "打赏", "赞赏", "订阅", "关注", "下载", "安装",
        "分享", "点赞", "验证码", "验证", "评论", "回复", "广告", "领取", "优惠", "续费",
        "开通", "充值", "二维码", "小程序", "打开app", "打开APP", "app内打开", "在浏览器打开",
    )

    private val BLOCKED_TAGS = setOf("input", "textarea", "select", "option", "form")

    fun matchesPhrase(text: String): Boolean {
        val normalized = text.replace(Regex("\\s+"), "")
        if (normalized.isEmpty() || normalized.length > 40) return false
        return PHRASES.any { phrase ->
            if (phrase == "展开") {
                normalized == "展开" || normalized == "展开阅读" || normalized == "展开更多"
            } else {
                normalized.contains(phrase)
            }
        }
    }

    fun isSafe(candidate: L3Candidate): Boolean {
        if (!candidate.visible) return false
        if (candidate.rectWidth < 2 || candidate.rectHeight < 2) return false
        if (candidate.rectHeight > 200 || candidate.rectWidth > 1000) return false
        val tag = candidate.tag.lowercase()
        if (tag in BLOCKED_TAGS) return false
        if (candidate.inForm || candidate.submit) return false
        if (candidate.type?.lowercase() == "submit") return false
        if (!matchesPhrase(candidate.text)) return false
        val text = candidate.text.lowercase()
        if (BLOCKED_WORDS.any { text.contains(it.lowercase()) }) return false
        val href = candidate.href?.trim().orEmpty()
        if (href.isNotEmpty() && href != "#" && !href.startsWith("javascript:") && !href.startsWith("#")) {
            return false
        }
        return true
    }

    fun pick(candidates: List<L3Candidate>, clickedKeys: Set<String>): L3Candidate? =
        candidates
            .filter { isSafe(it) && it.key !in clickedKeys }
            .sortedWith(compareByDescending<L3Candidate> { it.leaf }.thenBy { it.rectWidth * it.rectHeight }.thenBy { it.index })
            .firstOrNull()
}
