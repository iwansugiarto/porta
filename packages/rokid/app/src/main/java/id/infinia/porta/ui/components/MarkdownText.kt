package id.infinia.porta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.infinia.porta.ui.theme.*

/**
 * Lightweight Compose markdown renderer.
 *
 * Handles the most common markdown elements without external dependencies:
 * - **Bold**, *italic*, `inline code`
 * - Code blocks (``` fenced)
 * - Headers (# ## ###)
 * - Lists (- / * / numbered)
 * - Links [text](url) → rendered as underlined text
 * - Horizontal rules (---)
 *
 * This is intentionally simpler than a full CommonMark parser to keep
 * the APK size small and avoid complex dependency chains.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
) {
    val lines = markdown.lines()
    val blocks = parseBlocks(lines)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (block in blocks) {
            when (block) {
                is MdBlock.Paragraph -> {
                    Text(
                        text = parseInline(block.text),
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                is MdBlock.Heading -> {
                    val (size, weight) = when (block.level) {
                        1 -> 20.sp to FontWeight.Bold
                        2 -> 17.sp to FontWeight.Bold
                        3 -> 15.sp to FontWeight.SemiBold
                        else -> 14.sp to FontWeight.Medium
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = parseInline(block.text),
                        fontSize = size,
                        fontWeight = weight,
                        lineHeight = (size.value + 6).sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                is MdBlock.CodeBlock -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(6.dp))
                            .padding(8.dp)
                            .horizontalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = block.code,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                        )
                    }
                }
                is MdBlock.ListItem -> {
                    Row(modifier = Modifier.padding(start = 8.dp)) {
                        Text(
                            text = block.bullet,
                            fontSize = 14.sp,
                            color = PortaTertiary,
                            modifier = Modifier.width(20.dp)
                        )
                        Text(
                            text = parseInline(block.text),
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                is MdBlock.HorizontalRule -> {
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

// ── Block parsing ──

private sealed class MdBlock {
    data class Paragraph(val text: String) : MdBlock()
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class CodeBlock(val code: String, val language: String = "") : MdBlock()
    data class ListItem(val bullet: String, val text: String) : MdBlock()
    data object HorizontalRule : MdBlock()
}

private fun parseBlocks(lines: List<String>): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    var i = 0

    while (i < lines.size) {
        val line = lines[i]

        // Fenced code block
        if (line.trimStart().startsWith("```")) {
            val lang = line.trimStart().removePrefix("```").trim()
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                codeLines.add(lines[i])
                i++
            }
            blocks.add(MdBlock.CodeBlock(codeLines.joinToString("\n"), lang))
            i++ // skip closing ```
            continue
        }

        // Heading
        val headingMatch = Regex("^(#{1,4})\\s+(.+)").find(line)
        if (headingMatch != null) {
            blocks.add(MdBlock.Heading(
                headingMatch.groupValues[1].length,
                headingMatch.groupValues[2]
            ))
            i++
            continue
        }

        // Horizontal rule
        if (line.trim().matches(Regex("^-{3,}$|^\\*{3,}$|^_{3,}$"))) {
            blocks.add(MdBlock.HorizontalRule)
            i++
            continue
        }

        // Unordered list
        val ulMatch = Regex("^\\s*[-*+]\\s+(.+)").find(line)
        if (ulMatch != null) {
            blocks.add(MdBlock.ListItem("•", ulMatch.groupValues[1]))
            i++
            continue
        }

        // Ordered list
        val olMatch = Regex("^\\s*(\\d+)[.)\\s]+(.+)").find(line)
        if (olMatch != null) {
            blocks.add(MdBlock.ListItem("${olMatch.groupValues[1]}.", olMatch.groupValues[2]))
            i++
            continue
        }

        // Empty line → skip
        if (line.isBlank()) {
            i++
            continue
        }

        // Paragraph: collect consecutive non-empty, non-special lines
        val paraLines = mutableListOf(line)
        i++
        while (i < lines.size &&
            lines[i].isNotBlank() &&
            !lines[i].trimStart().startsWith("```") &&
            !lines[i].trimStart().startsWith("#") &&
            !lines[i].trim().matches(Regex("^-{3,}$")) &&
            !Regex("^\\s*[-*+]\\s+").containsMatchIn(lines[i]) &&
            !Regex("^\\s*\\d+[.)\\s]+").containsMatchIn(lines[i])
        ) {
            paraLines.add(lines[i])
            i++
        }
        blocks.add(MdBlock.Paragraph(paraLines.joinToString(" ")))
    }

    return blocks
}

// ── Inline parsing (bold, italic, code, links) ──

private fun parseInline(text: String): AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            // Bold + italic ***text***
            if (i + 2 < text.length && text.substring(i, i + 3) == "***") {
                val end = text.indexOf("***", i + 3)
                if (end > 0) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
                        append(text.substring(i + 3, end))
                    }
                    i = end + 3
                    continue
                }
            }

            // Bold **text** or __text__
            if (i + 1 < text.length && (text.substring(i, i + 2) == "**" || text.substring(i, i + 2) == "__")) {
                val marker = text.substring(i, i + 2)
                val end = text.indexOf(marker, i + 2)
                if (end > 0) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(text.substring(i + 2, end))
                    }
                    i = end + 2
                    continue
                }
            }

            // Italic *text* or _text_
            if (text[i] == '*' || text[i] == '_') {
                val marker = text[i]
                val end = text.indexOf(marker, i + 1)
                if (end > 0 && end > i + 1) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                    continue
                }
            }

            // Inline code `text`
            if (text[i] == '`') {
                val end = text.indexOf('`', i + 1)
                if (end > 0) {
                    withStyle(SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = Color(0x1AFFFFFF),
                        fontSize = 13.sp
                    )) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                    continue
                }
            }

            // Link [text](url)
            if (text[i] == '[') {
                val closeBracket = text.indexOf(']', i + 1)
                if (closeBracket > 0 && closeBracket + 1 < text.length && text[closeBracket + 1] == '(') {
                    val closeParen = text.indexOf(')', closeBracket + 2)
                    if (closeParen > 0) {
                        val linkText = text.substring(i + 1, closeBracket)
                        withStyle(SpanStyle(
                            color = PortaTertiary,
                            textDecoration = TextDecoration.Underline
                        )) {
                            append(linkText)
                        }
                        i = closeParen + 1
                        continue
                    }
                }
            }

            // Regular character
            append(text[i])
            i++
        }
    }
}
