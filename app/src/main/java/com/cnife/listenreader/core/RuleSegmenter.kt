package com.cnife.listenreader.core

import com.cnife.listenreader.model.SegmentDraft
import com.cnife.listenreader.model.SegmentKind

object RuleSegmenter {

    const val MAX_SEGMENT_CHARS = 220
    const val MIN_SEGMENT_CHARS = 12

    private val TERMINATORS = charArrayOf('。', '！', '？', '!', '?', '；', ';', '…')
    private val CLOSERS = charArrayOf('”', '』', '」', '』', '）', ')', '”', '"', '\'', '’', '】', '》')
    private val SOFT_PUNCT = charArrayOf('，', '、', '：', ':', ',', '；', ';', '。', '！', '？', '!', '?', '…')

    fun segment(title: String, body: String): List<SegmentDraft> {
        val drafts = mutableListOf<SegmentDraft>()
        val cleanTitle = title.trim()
        if (cleanTitle.isNotEmpty()) {
            drafts += SegmentDraft(cleanTitle, SegmentKind.HEADING)
        }
        for (paragraph in splitParagraphs(body)) {
            drafts += segmentParagraph(paragraph)
        }
        return drafts
    }

    private fun splitParagraphs(body: String): List<String> {
        val normalized = body.replace("\r\n", "\n").replace('\r', '\n')
        return normalized
            .split(Regex("\n\\s*\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun segmentParagraph(paragraph: String): List<SegmentDraft> {
        val lines = paragraph.lines()
        val firstNonBlank = lines.firstOrNull { it.isNotBlank() }?.trim() ?: return emptyList()

        if (firstNonBlank.startsWith("#")) {
            val text = lines
                .joinToString(" ") { it.trim() }
                .replace(Regex("^#+\\s*"), "")
                .replace(Regex("\\s+#+\\s*$"), "")
                .trim()
            return if (text.isEmpty()) emptyList() else listOf(SegmentDraft(text, SegmentKind.HEADING))
        }

        val nonBlank = lines.filter { it.isNotBlank() }
        val isQuote = nonBlank.isNotEmpty() && nonBlank.all { it.trimStart().startsWith(">") }
        val kind = if (isQuote) SegmentKind.QUOTE else SegmentKind.PARAGRAPH
        val cleaned = if (isQuote) {
            lines.map { it.trimStart().removePrefix(">").trimStart() }
        } else {
            lines
        }

        val sentences = cleaned
            .flatMap { splitSentences(it) }
            .flatMap { splitLong(it) }
            .filter { it.isNotBlank() }

        return mergeShort(sentences).map { SegmentDraft(it, kind) }
    }

    private fun splitSentences(line: String): List<String> {
        val out = mutableListOf<String>()
        val buffer = StringBuilder()
        var i = 0
        while (i < line.length) {
            val c = line[i]
            buffer.append(c)
            if (c in TERMINATORS) {
                var j = i + 1
                while (j < line.length && (line[j] in TERMINATORS || line[j] in CLOSERS)) {
                    buffer.append(line[j])
                    j++
                }
                val text = buffer.toString().trim()
                if (text.isNotEmpty()) out += text
                buffer.clear()
                i = j
            } else {
                i++
            }
        }
        val rest = buffer.toString().trim()
        if (rest.isNotEmpty()) out += rest
        return out
    }

    private fun splitLong(sentence: String): List<String> {
        if (sentence.length <= MAX_SEGMENT_CHARS) return listOf(sentence)

        val out = mutableListOf<String>()
        var remaining = sentence
        while (remaining.length > MAX_SEGMENT_CHARS) {
            val window = remaining.substring(0, MAX_SEGMENT_CHARS)
            var cut = -1
            for (k in window.indices.reversed()) {
                if (window[k] in SOFT_PUNCT) {
                    cut = k + 1
                    break
                }
            }
            if (cut < MAX_SEGMENT_CHARS / 4) cut = MAX_SEGMENT_CHARS
            val piece = remaining.substring(0, cut).trim()
            if (piece.isNotEmpty()) out += piece
            remaining = remaining.substring(cut).trimStart()
        }
        if (remaining.isNotEmpty()) out += remaining
        return out
    }

    private fun mergeShort(sentences: List<String>): List<String> {
        val list = sentences.toMutableList()
        var i = 0
        while (i < list.size) {
            val current = list[i]
            if (current.length >= MIN_SEGMENT_CHARS || list.size == 1) {
                i++
                continue
            }
            val prev = if (i > 0) list[i - 1] else null
            val next = if (i + 1 < list.size) list[i + 1] else null
            when {
                prev != null && prev.length + current.length <= MAX_SEGMENT_CHARS -> {
                    list[i - 1] = prev + current
                    list.removeAt(i)
                }
                next != null && current.length + next.length <= MAX_SEGMENT_CHARS -> {
                    list[i] = current + next
                    list.removeAt(i + 1)
                }
                else -> i++
            }
        }
        return list
    }
}
