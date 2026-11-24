package app.acloudroid.qwq.ui.component

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTextTest {

    @Test
    fun testParseMarkdownBlocks_headingsAndLists() {
        val markdown = """
            更新说明：
            ## 更新内容

            - **修复 Shizuku 无法正常调用的 bug**
            - **优化照片流视图**，现在可通过双指捏合调整显示列数
            1. 第一步
            2. 第二步

            > 软件备注信息

            ---
            ```kt
            val x = 10
            ```
        """.trimIndent()

        val blocks = parseMarkdownBlocks(markdown)

        assertEquals(9, blocks.size)
        assertTrue(blocks[0] is MarkdownBlock.Paragraph)
        assertEquals("更新说明：", (blocks[0] as MarkdownBlock.Paragraph).text)

        assertTrue(blocks[1] is MarkdownBlock.Heading)
        val heading = blocks[1] as MarkdownBlock.Heading
        assertEquals(2, heading.level)
        assertEquals("更新内容", heading.text)

        assertTrue(blocks[2] is MarkdownBlock.BulletListItem)
        val bullet1 = blocks[2] as MarkdownBlock.BulletListItem
        assertEquals("**修复 Shizuku 无法正常调用的 bug**", bullet1.text)

        assertTrue(blocks[3] is MarkdownBlock.BulletListItem)

        assertTrue(blocks[4] is MarkdownBlock.NumberedListItem)
        val num1 = blocks[4] as MarkdownBlock.NumberedListItem
        assertEquals("1.", num1.index)
        assertEquals("第一步", num1.text)

        assertTrue(blocks[5] is MarkdownBlock.NumberedListItem)
        val num2 = blocks[5] as MarkdownBlock.NumberedListItem
        assertEquals("2.", num2.index)
        assertEquals("第二步", num2.text)

        assertTrue(blocks[6] is MarkdownBlock.Blockquote)
        assertEquals("软件备注信息", (blocks[6] as MarkdownBlock.Blockquote).text)

        assertTrue(blocks[7] is MarkdownBlock.HorizontalRule)

        assertTrue(blocks[8] is MarkdownBlock.CodeBlock)
        val codeBlock = blocks[8] as MarkdownBlock.CodeBlock
        assertEquals("kt", codeBlock.language)
        assertEquals("val x = 10", codeBlock.code)
    }

    @Test
    fun testBuildMarkdownAnnotatedString_inlineFormatting() {
        val raw = "Hello **world**, *italic*, `code`, ~~strike~~ and [link](https://example.com)"
        val annotated = buildMarkdownAnnotatedString(
            input = raw,
            primaryColor = Color.Blue,
            surfaceVariantColor = Color.Gray,
            onSurfaceVariantColor = Color.Black
        )

        assertEquals("Hello world, italic,  code , strike and link", annotated.text)
    }
}
