package com.cnife.listenreader.core

import com.cnife.listenreader.model.SegmentDraft
import com.cnife.listenreader.model.SegmentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleSegmenterTest {

    @Test
    fun `splits paragraphs into sentences at terminal punctuation`() {
        val body = "这是用于测试句号切分的足够长的第一句。第二句也应该足够长才能保持独立！第三句继续验证问号切分是否有效？"

        val drafts = RuleSegmenter.segment("", body)

        assertEquals(3, drafts.size)
        assertEquals("这是用于测试句号切分的足够长的第一句。", drafts[0].text)
        assertEquals("第二句也应该足够长才能保持独立！", drafts[1].text)
        assertEquals("第三句继续验证问号切分是否有效？", drafts[2].text)
        assertEquals(listOf(SegmentKind.PARAGRAPH, SegmentKind.PARAGRAPH, SegmentKind.PARAGRAPH), drafts.map { it.kind })
    }

    @Test
    fun `keeps every sentence segment at most 220 characters`() {
        val sentence = "这是一段用于测试超长切分的句子内容。"
        val body = sentence.repeat(15)

        val drafts = RuleSegmenter.segment("", body)

        assertTrue(drafts.all { it.text.length <= RuleSegmenter.MAX_SEGMENT_CHARS })
        assertEquals(body, drafts.joinToString("") { it.text })
    }

    @Test
    fun `hard splits an unpunctuated long sentence`() {
        val body = "字".repeat(300)

        val drafts = RuleSegmenter.segment("", body)

        assertEquals(2, drafts.size)
        assertTrue(drafts.all { it.text.length <= RuleSegmenter.MAX_SEGMENT_CHARS })
        assertEquals(body, drafts.joinToString("") { it.text })
    }

    @Test
    fun `merges very short sentences into the adjacent sentence`() {
        val body = "好。这是一个足够长的正常句子用于验证短句合并规则。"

        val drafts = RuleSegmenter.segment("", body)

        assertEquals(1, drafts.size)
        assertEquals("好。这是一个足够长的正常句子用于验证短句合并规则。", drafts[0].text)
    }

    @Test
    fun `markdown heading paragraph becomes one heading segment`() {
        val body = "# 第一节标题\n\n正文第一段足够长以保持独立。正文第二段也足够长以保持独立。"

        val drafts = RuleSegmenter.segment("", body)

        assertEquals(SegmentKind.HEADING, drafts.first().kind)
        assertEquals("第一节标题", drafts.first().text)
        assertTrue(drafts.drop(1).all { it.kind == SegmentKind.PARAGRAPH })
        assertEquals(3, drafts.size)
    }

    @Test
    fun `title is emitted as a leading heading segment`() {
        val drafts = RuleSegmenter.segment("文章标题", "这是一段足够长的正文句子用于测试标题。")

        assertEquals(SegmentKind.HEADING, drafts.first().kind)
        assertEquals("文章标题", drafts.first().text)
    }

    @Test
    fun `preserves paragraph order and is deterministic`() {
        val body = "第一段足够长的句子。\n\n第二段足够长的句子。\n\n第三段足够长的句子。"
        val first = RuleSegmenter.segment("", body)
        val second = RuleSegmenter.segment("", body)

        assertEquals(first, second)
        assertEquals(3, first.size)
        assertEquals("第一段足够长的句子。", first[0].text)
        assertEquals("第二段足够长的句子。", first[1].text)
        assertEquals("第三段足够长的句子。", first[2].text)
    }

    @Test
    fun `empty title and body produce no segments`() {
        val drafts: List<SegmentDraft> = RuleSegmenter.segment("   ", "\n\n  \n")

        assertTrue(drafts.isEmpty())
    }
}
