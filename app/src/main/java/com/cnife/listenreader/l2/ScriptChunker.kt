package com.cnife.listenreader.l2

/**
 * 把 Markdown 正文切成不超过 [MAX_CHUNK_CHARS] 的块，优先在段落边界切分。
 * 单块超长时按换行/句末标点硬切，保证任何块都不超过上限。
 */
object ScriptChunker {

    const val MAX_CHUNK_CHARS = 8000

    fun chunk(markdown: String, maxChars: Int = MAX_CHUNK_CHARS): List<String> {
        require(maxChars > 0) { "maxChars 必须为正数" }
        val normalized = markdown.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (normalized.isEmpty()) return emptyList()

        val chunks = mutableListOf<String>()
        val current = StringBuilder()

        fun flush() {
            val text = current.toString().trim()
            if (text.isNotEmpty()) chunks += text
            current.clear()
        }

        for (block in splitBlocks(normalized)) {
            if (block.length > maxChars) {
                flush()
                chunks += hardSplit(block, maxChars)
                continue
            }
            val separator = if (current.isEmpty()) 0 else 2
            if (current.length + separator + block.length > maxChars) flush()
            if (current.isNotEmpty()) current.append("\n\n")
            current.append(block)
        }
        flush()
        return chunks
    }

    private fun splitBlocks(text: String): List<String> =
        text.split(Regex("\n\\s*\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun hardSplit(text: String, maxChars: Int): List<String> {
        val out = mutableListOf<String>()
        var rest = text
        while (rest.length > maxChars) {
            val window = rest.substring(0, maxChars)
            var cut = maxChars
            for (k in window.indices.reversed()) {
                if (window[k] in HARD_BREAKS) {
                    cut = k + 1
                    break
                }
            }
            if (cut < maxChars / 2) cut = maxChars
            val piece = rest.substring(0, cut).trim()
            if (piece.isNotEmpty()) out += piece
            rest = rest.substring(cut).trimStart()
        }
        if (rest.isNotEmpty()) out += rest.trim()
        return out
    }

    private val HARD_BREAKS = charArrayOf(
        '\n', '。', '！', '？', '.', '!', '?', '；', ';', '，', ',', '、',
    )
}
