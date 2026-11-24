package app.acloudroid.qwq.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Parsed block elements representing Markdown structures.
 */
sealed class MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock()
    data class BulletListItem(val text: String, val level: Int = 0) : MarkdownBlock()
    data class NumberedListItem(val index: String, val text: String, val level: Int = 0) : MarkdownBlock()
    data class Blockquote(val text: String) : MarkdownBlock()
    data class CodeBlock(val code: String, val language: String = "") : MarkdownBlock()
    data object HorizontalRule : MarkdownBlock()
    data class Paragraph(val text: String) : MarkdownBlock()
}

/**
 * Renders raw Markdown text in Jetpack Compose with Material 3 styling.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    val blocks = remember(markdown) { parseMarkdownBlocks(markdown) }

    Column(modifier = modifier.fillMaxWidth()) {
        blocks.forEachIndexed { index, block ->
            if (index > 0) {
                Spacer(modifier = Modifier.height(6.dp))
            }
            RenderMarkdownBlock(block = block, defaultColor = color)
        }
    }
}

@Composable
private fun RenderMarkdownBlock(
    block: MarkdownBlock,
    defaultColor: Color
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    when (block) {
        is MarkdownBlock.Heading -> {
            val style = when (block.level) {
                1 -> MaterialTheme.typography.titleLarge
                2 -> MaterialTheme.typography.titleMedium
                3 -> MaterialTheme.typography.titleSmall
                else -> MaterialTheme.typography.bodyLarge
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = remember(block.text) {
                    buildMarkdownAnnotatedString(block.text, primaryColor, surfaceVariant, onSurfaceVariant)
                },
                style = style,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
        }

        is MarkdownBlock.BulletListItem -> {
            val indentDp = (block.level * 12).dp
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = indentDp + 4.dp),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = "•",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = primaryColor,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(
                    text = remember(block.text) {
                        buildMarkdownAnnotatedString(block.text, primaryColor, surfaceVariant, onSurfaceVariant)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = defaultColor,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        is MarkdownBlock.NumberedListItem -> {
            val indentDp = (block.level * 12).dp
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = indentDp + 4.dp),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = block.index,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = primaryColor,
                    modifier = Modifier.padding(end = 6.dp)
                )
                Text(
                    text = remember(block.text) {
                        buildMarkdownAnnotatedString(block.text, primaryColor, surfaceVariant, onSurfaceVariant)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = defaultColor,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        is MarkdownBlock.Blockquote -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
            ) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(24.dp)
                        .background(primaryColor.copy(alpha = 0.6f), shape = RoundedCornerShape(2.dp))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = remember(block.text) {
                        buildMarkdownAnnotatedString(block.text, primaryColor, surfaceVariant, onSurfaceVariant)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                    color = defaultColor.copy(alpha = 0.9f)
                )
            }
        }

        is MarkdownBlock.CodeBlock -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(surfaceVariant, shape = RoundedCornerShape(8.dp))
                    .padding(8.dp)
                    .horizontalScroll(rememberScrollState())
            ) {
                Text(
                    text = block.code,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    ),
                    color = onSurfaceVariant
                )
            }
        }

        is MarkdownBlock.HorizontalRule -> {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 6.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }

        is MarkdownBlock.Paragraph -> {
            Text(
                text = remember(block.text) {
                    buildMarkdownAnnotatedString(block.text, primaryColor, surfaceVariant, onSurfaceVariant)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = defaultColor
            )
        }
    }
}

/**
 * Parses raw Markdown text into block-level elements.
 */
fun parseMarkdownBlocks(rawText: String): List<MarkdownBlock> {
    if (rawText.isBlank()) return emptyList()

    val lines = rawText.lines()
    val blocks = mutableListOf<MarkdownBlock>()

    var inCodeBlock = false
    val codeBlockBuilder = StringBuilder()
    var codeLanguage = ""

    val paragraphBuilder = StringBuilder()

    fun flushParagraph() {
        if (paragraphBuilder.isNotBlank()) {
            blocks.add(MarkdownBlock.Paragraph(paragraphBuilder.toString().trim()))
            paragraphBuilder.clear()
        }
    }

    for (line in lines) {
        val trimmed = line.trim()

        // Code block toggle
        if (trimmed.startsWith("```")) {
            if (inCodeBlock) {
                blocks.add(MarkdownBlock.CodeBlock(codeBlockBuilder.toString().trimEnd(), codeLanguage))
                codeBlockBuilder.clear()
                codeLanguage = ""
                inCodeBlock = false
            } else {
                flushParagraph()
                inCodeBlock = true
                codeLanguage = trimmed.removePrefix("```").trim()
            }
            continue
        }

        if (inCodeBlock) {
            codeBlockBuilder.append(line).append("\n")
            continue
        }

        // Horizontal Rule
        if (trimmed == "---" || trimmed == "***" || trimmed == "___") {
            flushParagraph()
            blocks.add(MarkdownBlock.HorizontalRule)
            continue
        }

        // Blank line ends current paragraph
        if (trimmed.isEmpty()) {
            flushParagraph()
            continue
        }

        // Headings
        if (trimmed.startsWith("#")) {
            val level = trimmed.takeWhile { it == '#' }.length
            if (level in 1..6 && trimmed.length > level && trimmed[level] == ' ') {
                flushParagraph()
                val headingText = trimmed.substring(level + 1).trim()
                blocks.add(MarkdownBlock.Heading(level, headingText))
                continue
            }
        }

        // Blockquote
        if (trimmed.startsWith("> ")) {
            flushParagraph()
            blocks.add(MarkdownBlock.Blockquote(trimmed.removePrefix("> ").trim()))
            continue
        }

        // Bullet List Items (- item, * item, + item)
        val bulletMatch = Regex("""^(\s*)([-*+])\s+(.*)$""").find(line)
        if (bulletMatch != null) {
            flushParagraph()
            val indentSpaces = bulletMatch.groupValues[1].length
            val itemText = bulletMatch.groupValues[3].trim()
            val level = indentSpaces / 2
            blocks.add(MarkdownBlock.BulletListItem(itemText, level))
            continue
        }

        // Numbered List Items (1. item, 2) item)
        val numberedMatch = Regex("""^(\s*)(\d+[\.\)])\s+(.*)$""").find(line)
        if (numberedMatch != null) {
            flushParagraph()
            val indentSpaces = numberedMatch.groupValues[1].length
            val numberIndex = numberedMatch.groupValues[2]
            val itemText = numberedMatch.groupValues[3].trim()
            val level = indentSpaces / 2
            blocks.add(MarkdownBlock.NumberedListItem(numberIndex, itemText, level))
            continue
        }

        // Otherwise append to paragraph
        if (paragraphBuilder.isNotEmpty()) {
            paragraphBuilder.append("\n")
        }
        paragraphBuilder.append(line)
    }

    flushParagraph()

    if (inCodeBlock && codeBlockBuilder.isNotEmpty()) {
        blocks.add(MarkdownBlock.CodeBlock(codeBlockBuilder.toString().trimEnd(), codeLanguage))
    }

    return blocks
}

/**
 * Builds an [AnnotatedString] from a inline markdown string supporting bold, italic, code, strikethrough, and links.
 */
fun buildMarkdownAnnotatedString(
    input: String,
    primaryColor: Color,
    surfaceVariantColor: Color,
    onSurfaceVariantColor: Color
): AnnotatedString {
    return buildAnnotatedString {
        // Regex pattern for inline markdown elements:
        // Group 1 & 2: Link [label](url)
        // Group 3 & 4: Bold **text** or __text__
        // Group 5 & 6: Italic *text* or _text_
        // Group 7: Inline Code `code`
        // Group 8: Strikethrough ~~text~~
        val pattern = Regex("""\[([^\]]+)\]\(([^)]+)\)|\*\*([^*]+)\*\*|__([^_]+)__|(?<!\*)\*([^*]+)\*(?!\*)|(?<!_)_([^_]+)_(?!_)|`([^`]+)`|~~([^~]+)~~""")

        var currentIndex = 0
        val matches = pattern.findAll(input)

        for (match in matches) {
            if (match.range.first > currentIndex) {
                append(input.substring(currentIndex, match.range.first))
            }

            val linkLabel = match.groups[1]?.value
            val linkUrl = match.groups[2]?.value
            val boldText = match.groups[3]?.value ?: match.groups[4]?.value
            val italicText = match.groups[5]?.value ?: match.groups[6]?.value
            val codeText = match.groups[7]?.value
            val strikeText = match.groups[8]?.value

            when {
                linkLabel != null && linkUrl != null -> {
                    val linkStyle = SpanStyle(
                        color = primaryColor,
                        textDecoration = TextDecoration.Underline
                    )
                    try {
                        withLink(LinkAnnotation.Url(linkUrl)) {
                            withStyle(linkStyle) {
                                append(linkLabel)
                            }
                        }
                    } catch (_: Throwable) {
                        withStyle(linkStyle) {
                            append(linkLabel)
                        }
                    }
                }
                boldText != null -> {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(boldText)
                    }
                }
                italicText != null -> {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(italicText)
                    }
                }
                codeText != null -> {
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = surfaceVariantColor,
                            color = onSurfaceVariantColor,
                            fontSize = 13.sp
                        )
                    ) {
                        append(" $codeText ")
                    }
                }
                strikeText != null -> {
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                        append(strikeText)
                    }
                }
                else -> {
                    append(match.value)
                }
            }

            currentIndex = match.range.last + 1
        }

        if (currentIndex < input.length) {
            append(input.substring(currentIndex))
        }
    }
}
