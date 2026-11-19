package com.cnife.listenreader.ui

import com.cnife.listenreader.ui.player.MarkdownBlock
import com.cnife.listenreader.ui.player.parseMarkdownBlocks
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingMarkdownTest {

    @Test
    fun `parses headings paragraphs quotes lists and code`() {
        val markdown = """
            # 大标题

            第一段原文。
            第二行原文。

            ## 二级标题

            > 引用一
            > 引用二

            - 无序项
            1. 有序项

            ```
            val x = 1
            ```
        """.trimIndent()

        val blocks = parseMarkdownBlocks(markdown)

        assertEquals(
            listOf(
                MarkdownBlock.Heading(1, "大标题"),
                MarkdownBlock.Paragraph("第一段原文。\n第二行原文。"),
                MarkdownBlock.Heading(2, "二级标题"),
                MarkdownBlock.Quote("引用一\n引用二"),
                MarkdownBlock.ListItem("无序项"),
                MarkdownBlock.ListItem("有序项"),
                MarkdownBlock.Code("val x = 1"),
            ),
            blocks,
        )
    }

    @Test
    fun `plain text without markup becomes one paragraph`() {
        assertEquals(
            listOf(MarkdownBlock.Paragraph("一句普通原文。")),
            parseMarkdownBlocks("一句普通原文。"),
        )
    }
}
