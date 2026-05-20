package id.infinia.porta.ui.components

import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.infinia.porta.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Shared HTTP client for loading network images — avoids per-image thread pool leak. */
private val imageHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .build()

/**
 * Lightweight Compose markdown renderer.
 *
 * Handles the most common markdown elements without external dependencies:
 * - **Bold**, *italic*, `inline code`, ~~strikethrough~~
 * - Code blocks (``` fenced)
 * - Headers (# ## ###)
 * - Lists (- / * / numbered)
 * - Links [text](url) → rendered as underlined text
 * - Images ![alt](url) → rendered inline (base64 data: URI or network URL)
 * - Tables (| col | col |)
 * - Blockquotes (> text)
 * - Horizontal rules (---)
 *
 * This is intentionally simpler than a full CommonMark parser to keep
 * the APK size small and avoid complex dependency chains.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    annotatable: Boolean = false,
    annotations: Map<Int, String> = emptyMap(),
    onAnnotationRequested: ((Int) -> Unit)? = null,
) {
    val lines = markdown.lines()
    val blocks = parseBlocks(lines)
    val uriHandler = LocalUriHandler.current
    val textColor = MaterialTheme.colorScheme.onSurface
    val haptic = LocalHapticFeedback.current

    // Safe link opener — only handles http/https, ignores file:/// and other schemes
    val safeOpenUri: (String) -> Unit = { url ->
        try {
            if (url.startsWith("http://") || url.startsWith("https://")) {
                uriHandler.openUri(url)
            }
            // Silently ignore file:/// and other non-web URIs
        } catch (_: Exception) {
            // Prevent crash from ActivityNotFoundException or SecurityException
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((blockIdx, block) in blocks.withIndex()) {
            // Wrap each block in an annotatable container if enabled
            val hasAnnotation = annotatable && annotations.containsKey(blockIdx)
            val annotationAccentColor = PortaPrimary
            val blockModifier = if (annotatable) {
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (hasAnnotation) {
                            Modifier.drawBehind {
                                drawLine(
                                    color = annotationAccentColor,
                                    start = Offset(0f, 0f),
                                    end = Offset(0f, size.height),
                                    strokeWidth = 3.dp.toPx()
                                )
                            }.padding(start = 6.dp)
                        } else Modifier
                    )
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onAnnotationRequested?.invoke(blockIdx)
                    }
            } else {
                Modifier.fillMaxWidth()
            }

            Box(modifier = blockModifier) {
                Column {
                    when (block) {
                        is MdBlock.Paragraph -> {
                            val annotated = parseInlineLinked(block.text, safeOpenUri)
                            Text(
                                text = annotated,
                                style = TextStyle(
                                    fontSize = 14.sp,
                                    lineHeight = 20.sp,
                                    color = textColor
                                )
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
                            val annotated = parseInlineLinked(block.text, safeOpenUri)
                            Text(
                                text = annotated,
                                style = TextStyle(
                                    fontSize = size,
                                    fontWeight = weight,
                                    lineHeight = (size.value + 6).sp,
                                    color = textColor
                                )
                            )
                        }
                        is MdBlock.CodeBlock -> {
                            val clipboardManager = LocalClipboardManager.current
                            val hapticFb = LocalHapticFeedback.current
                            var copied by remember { mutableStateOf(false) }

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(6.dp))
                            ) {
                                // Code content with horizontal scroll
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(end = 32.dp) // Reserve space for copy button
                                        .padding(8.dp)
                                        .horizontalScroll(rememberScrollState())
                                ) {
                                    Text(
                                        text = block.code,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                        lineHeight = 16.sp,
                                        color = textColor.copy(alpha = 0.85f)
                                    )
                                }
                                // Copy button in top-right corner
                                IconButton(
                                    onClick = {
                                        clipboardManager.setText(AnnotatedString(block.code))
                                        hapticFb.performHapticFeedback(HapticFeedbackType.LongPress)
                                        copied = true
                                    },
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .size(28.dp)
                                        .padding(4.dp)
                                ) {
                                    Icon(
                                        Icons.Default.ContentCopy,
                                        contentDescription = "Copy code",
                                        modifier = Modifier.size(14.dp),
                                        tint = if (copied) PortaSuccess else textColor.copy(alpha = 0.3f)
                                    )
                                }
                            }

                            // Reset copied state after a delay
                            LaunchedEffect(copied) {
                                if (copied) {
                                    kotlinx.coroutines.delay(2000)
                                    copied = false
                                }
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
                                val annotated = parseInlineLinked(block.text, safeOpenUri)
                                Text(
                                    text = annotated,
                                    style = TextStyle(
                                        fontSize = 14.sp,
                                        lineHeight = 20.sp,
                                        color = textColor
                                    )
                                )
                            }
                        }
                        is MdBlock.Table -> {
                            MarkdownTable(table = block)
                        }
                        is MdBlock.Blockquote -> {
                            MarkdownBlockquote(text = block.text)
                        }
                        is MdBlock.ImageBlock -> {
                            MarkdownImageBlock(url = block.url, alt = block.alt)
                        }
                        is MdBlock.HorizontalRule -> {
                            Spacer(Modifier.height(4.dp))
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(textColor.copy(alpha = 0.1f))
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                    }

                    // Annotation indicator
                    if (hasAnnotation) {
                        Text(
                            "💬 ${annotations[blockIdx]}",
                            fontSize = 10.sp,
                            color = PortaPrimary.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

// ── Table rendering ──

@Composable
private fun MarkdownTable(table: MdBlock.Table) {
    val borderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val headerBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val textColor = MaterialTheme.colorScheme.onSurface
    val colCount = table.headers.size

    // Compute column weights: equal distribution
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, borderColor, RoundedCornerShape(6.dp))
    ) {
        Column {
            // Header row
            Row(
                modifier = Modifier
                    .background(headerBg)
                    .fillMaxWidth()
            ) {
                table.headers.forEachIndexed { i, header ->
                    val borderMod = if (i < colCount - 1) {
                        Modifier.drawBehind {
                            drawLine(
                                color = borderColor,
                                start = Offset(size.width, 0f),
                                end = Offset(size.width, size.height),
                                strokeWidth = 1.dp.toPx()
                            )
                        }
                    } else Modifier
                    Box(
                        modifier = Modifier
                            .widthIn(min = 60.dp, max = 200.dp)
                            .then(borderMod)
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = header.trim(),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = textColor,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            // Separator
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(borderColor)
            )
            // Data rows
            table.rows.forEachIndexed { rowIdx, row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (rowIdx % 2 == 0) Color.Transparent
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                        )
                ) {
                    for (i in 0 until colCount) {
                        val cell = row.getOrElse(i) { "" }
                        val borderMod = if (i < colCount - 1) {
                            Modifier.drawBehind {
                                drawLine(
                                    color = borderColor,
                                    start = Offset(size.width, 0f),
                                    end = Offset(size.width, size.height),
                                    strokeWidth = 1.dp.toPx()
                                )
                            }
                        } else Modifier
                        Box(
                            modifier = Modifier
                                .widthIn(min = 60.dp, max = 200.dp)
                                .then(borderMod)
                                .padding(horizontal = 8.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = cell.trim(),
                                fontSize = 12.sp,
                                color = textColor.copy(alpha = 0.85f),
                                lineHeight = 16.sp,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                // Row separator
                if (rowIdx < table.rows.size - 1) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(0.5.dp)
                            .background(borderColor.copy(alpha = 0.5f))
                    )
                }
            }
        }
    }
}

// ── Blockquote rendering ──

@Composable
private fun MarkdownBlockquote(text: String) {
    val borderColor = PortaTertiary
    val bgColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    val textColor = MaterialTheme.colorScheme.onSurface

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
    ) {
        // Left accent border
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(borderColor)
        )
        // Quote content — render inline markdown inside
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            val annotated = parseInline(text)
            Text(
                text = annotated,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontStyle = FontStyle.Italic,
                color = textColor.copy(alpha = 0.8f)
            )
        }
    }
}

// ── Image rendering ──

/**
 * Renders a markdown image block.
 *
 * Supports:
 * - data:image/png;base64,... (inline base64 from Antigravity screenshots)
 * - https://... (network images)
 * - file:///... (ignored for security, shows placeholder)
 */
@Composable
private fun MarkdownImageBlock(url: String, alt: String) {
    when {
        // Base64 data URI — decode inline
        url.startsWith("data:image/") -> {
            val base64Data = url.substringAfter("base64,", "")
            if (base64Data.isNotEmpty()) {
                val bitmap = remember(base64Data.hashCode()) {
                    try {
                        val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    } catch (e: Exception) {
                        Log.w("MarkdownText", "Failed to decode base64 image: ${e.message}")
                        null
                    }
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = alt.ifBlank { "Image" },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.FillWidth
                    )
                    if (alt.isNotBlank()) {
                        Text(
                            alt,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                } else {
                    ImagePlaceholder(alt.ifBlank { "Failed to decode image" })
                }
            } else {
                ImagePlaceholder(alt.ifBlank { "Invalid image data" })
            }
        }

        // Network URL — download and display
        url.startsWith("http://") || url.startsWith("https://") -> {
            NetworkImage(url = url, alt = alt)
        }

        // file:/// or other unsupported schemes — show placeholder
        else -> {
            ImagePlaceholder(alt.ifBlank { "Image: ${url.substringAfterLast("/")}" })
        }
    }
}

/**
 * Downloads and displays a network image.
 */
@Composable
private fun NetworkImage(url: String, alt: String) {
    var bitmap by remember(url) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var isLoading by remember(url) { mutableStateOf(true) }
    var error by remember(url) { mutableStateOf<String?>(null) }

    LaunchedEffect(url) {
        isLoading = true
        error = null
        try {
            val bytes = withContext(Dispatchers.IO) {
                val request = Request.Builder().url(url).build()
                val response = imageHttpClient.newCall(request).execute()
                if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                response.body?.bytes() ?: throw Exception("Empty response")
            }
            bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bitmap == null) error = "Cannot decode image"
        } catch (e: Exception) {
            Log.w("MarkdownText", "Failed to load network image: ${e.message}")
            error = e.message?.take(60)
        } finally {
            isLoading = false
        }
    }

    when {
        isLoading -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = PortaTertiary
                )
            }
        }
        bitmap != null -> {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = alt.ifBlank { "Image" },
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.FillWidth
            )
            if (alt.isNotBlank()) {
                Text(
                    alt,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        else -> {
            ImagePlaceholder(error ?: alt.ifBlank { "Failed to load image" })
        }
    }
}

@Composable
private fun ImagePlaceholder(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        )
    }
}

// ── Block parsing ──

private sealed class MdBlock {
    data class Paragraph(val text: String) : MdBlock()
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class CodeBlock(val code: String, val language: String = "") : MdBlock()
    data class ListItem(val bullet: String, val text: String) : MdBlock()
    data class ImageBlock(val alt: String, val url: String) : MdBlock()
    data class Table(val headers: List<String>, val alignments: List<String>, val rows: List<List<String>>) : MdBlock()
    data class Blockquote(val text: String) : MdBlock()
    data object HorizontalRule : MdBlock()
}

/** Regex for markdown image syntax: ![alt text](url) */
private val IMAGE_REGEX = Regex("""^!\[([^\]]*)\]\(([^)]+)\)\s*$""")

/** Regex for table row: | cell | cell | ... | */
private val TABLE_ROW_REGEX = Regex("""^\|(.+)\|$""")

/** Regex for table separator: |---|---|...| or |:---|:---:|---:| */
private val TABLE_SEP_REGEX = Regex("""^\|[\s:]*-{2,}[\s:]*(\|[\s:]*-{2,}[\s:]*)*\|$""")

/** Regex for blockquote: > text */
private val BLOCKQUOTE_REGEX = Regex("""^>\s?(.*)$""")

/** Regex for heading: # text, ## text, etc. */
private val HEADING_REGEX = Regex("""^(#{1,4})\s+(.+)""")

/** Regex for horizontal rule: --- or *** or ___ */
private val HR_REGEX = Regex("""^-{3,}$|^\*{3,}$|^_{3,}$""")

/** Regex for unordered list: - text, * text, + text */
private val UL_REGEX = Regex("""^\s*[-*+]\s+(.+)""")

/** Regex for ordered list: 1. text, 2) text */
private val OL_REGEX = Regex("""^\s*(\d+)[.)\s]+(.+)""")

private fun parseTableRow(line: String): List<String> {
    return line.trim().removePrefix("|").removeSuffix("|").split("|")
        .map { it.trim() }
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

        // Table: requires header row + separator row + at least one data row
        if (TABLE_ROW_REGEX.matches(line.trim()) && i + 1 < lines.size &&
            TABLE_SEP_REGEX.matches(lines[i + 1].trim())) {
            val headers = parseTableRow(line)
            val sepLine = lines[i + 1].trim()
            val alignments = sepLine.removePrefix("|").removeSuffix("|").split("|").map { cell ->
                val trimmed = cell.trim()
                when {
                    trimmed.startsWith(":") && trimmed.endsWith(":") -> "center"
                    trimmed.endsWith(":") -> "right"
                    else -> "left"
                }
            }
            i += 2 // skip header + separator
            val rows = mutableListOf<List<String>>()
            while (i < lines.size && TABLE_ROW_REGEX.matches(lines[i].trim())) {
                rows.add(parseTableRow(lines[i]))
                i++
            }
            blocks.add(MdBlock.Table(headers, alignments, rows))
            continue
        }

        // Blockquote: > text (collect consecutive > lines)
        val bqMatch = BLOCKQUOTE_REGEX.find(line)
        if (bqMatch != null) {
            val bqLines = mutableListOf(bqMatch.groupValues[1])
            i++
            while (i < lines.size) {
                val nextBq = BLOCKQUOTE_REGEX.find(lines[i])
                if (nextBq != null) {
                    bqLines.add(nextBq.groupValues[1])
                    i++
                } else break
            }
            blocks.add(MdBlock.Blockquote(bqLines.joinToString("\n")))
            continue
        }

        // Image: ![alt](url) — must be checked before paragraph to avoid swallowing
        val imageMatch = IMAGE_REGEX.find(line.trim())
        if (imageMatch != null) {
            blocks.add(MdBlock.ImageBlock(
                alt = imageMatch.groupValues[1],
                url = imageMatch.groupValues[2]
            ))
            i++
            continue
        }

        // Heading
        val headingMatch = HEADING_REGEX.find(line)
        if (headingMatch != null) {
            blocks.add(MdBlock.Heading(
                headingMatch.groupValues[1].length,
                headingMatch.groupValues[2]
            ))
            i++
            continue
        }

        // Horizontal rule
        if (HR_REGEX.matches(line.trim())) {
            blocks.add(MdBlock.HorizontalRule)
            i++
            continue
        }

        // Unordered list
        val ulMatch = UL_REGEX.find(line)
        if (ulMatch != null) {
            blocks.add(MdBlock.ListItem("•", ulMatch.groupValues[1]))
            i++
            continue
        }

        // Ordered list
        val olMatch = OL_REGEX.find(line)
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
            !HR_REGEX.matches(lines[i].trim()) &&
            !UL_REGEX.containsMatchIn(lines[i]) &&
            !OL_REGEX.containsMatchIn(lines[i]) &&
            IMAGE_REGEX.find(lines[i].trim()) == null &&
            BLOCKQUOTE_REGEX.find(lines[i]) == null &&
            !TABLE_ROW_REGEX.matches(lines[i].trim())
        ) {
            paraLines.add(lines[i])
            i++
        }
        blocks.add(MdBlock.Paragraph(paraLines.joinToString(" ")))
    }

    return blocks
}

// ── Inline parsing (bold, italic, code, strikethrough, links) ──

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

            // Strikethrough ~~text~~
            if (i + 1 < text.length && text.substring(i, i + 2) == "~~") {
                val end = text.indexOf("~~", i + 2)
                if (end > 0) {
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                        append(text.substring(i + 2, end))
                    }
                    i = end + 2
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

            // Inline image ![alt](url) — render as [alt] text placeholder in inline context
            if (text[i] == '!' && i + 1 < text.length && text[i + 1] == '[') {
                val closeBracket = text.indexOf(']', i + 2)
                if (closeBracket > 0 && closeBracket + 1 < text.length && text[closeBracket + 1] == '(') {
                    val closeParen = text.indexOf(')', closeBracket + 2)
                    if (closeParen > 0) {
                        val altText = text.substring(i + 2, closeBracket)
                        withStyle(SpanStyle(
                            color = PortaTertiary,
                            fontStyle = FontStyle.Italic
                        )) {
                            append("[${altText.ifBlank { "image" }}]")
                        }
                        i = closeParen + 1
                        continue
                    }
                }
            }

            // Link [text](url)
            if (text[i] == '[') {
                val closeBracket = text.indexOf(']', i + 1)
                if (closeBracket > 0 && closeBracket + 1 < text.length && text[closeBracket + 1] == '(') {
                    val closeParen = text.indexOf(')', closeBracket + 2)
                    if (closeParen > 0) {
                        val linkText = text.substring(i + 1, closeBracket)
                        val linkUrl = text.substring(closeBracket + 2, closeParen)
                        pushStringAnnotation(tag = "URL", annotation = linkUrl)
                        withStyle(SpanStyle(
                            color = PortaTertiary,
                            textDecoration = TextDecoration.Underline
                        )) {
                            append(linkText)
                        }
                        pop()
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

/**
 * Variant of parseInline that makes links clickable using LinkAnnotation.
 * Used with standard Text() instead of deprecated ClickableText().
 */
private fun parseInlineLinked(text: String, onLinkClick: (String) -> Unit): AnnotatedString {
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

            // Strikethrough ~~text~~
            if (i + 1 < text.length && text.substring(i, i + 2) == "~~") {
                val end = text.indexOf("~~", i + 2)
                if (end > 0) {
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                        append(text.substring(i + 2, end))
                    }
                    i = end + 2
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

            // Inline image ![alt](url)
            if (text[i] == '!' && i + 1 < text.length && text[i + 1] == '[') {
                val closeBracket = text.indexOf(']', i + 2)
                if (closeBracket > 0 && closeBracket + 1 < text.length && text[closeBracket + 1] == '(') {
                    val closeParen = text.indexOf(')', closeBracket + 2)
                    if (closeParen > 0) {
                        val altText = text.substring(i + 2, closeBracket)
                        withStyle(SpanStyle(
                            color = PortaTertiary,
                            fontStyle = FontStyle.Italic
                        )) {
                            append("[${altText.ifBlank { "image" }}]")
                        }
                        i = closeParen + 1
                        continue
                    }
                }
            }

            // Link [text](url) — use LinkAnnotation for clickable links
            if (text[i] == '[') {
                val closeBracket = text.indexOf(']', i + 1)
                if (closeBracket > 0 && closeBracket + 1 < text.length && text[closeBracket + 1] == '(') {
                    val closeParen = text.indexOf(')', closeBracket + 2)
                    if (closeParen > 0) {
                        val linkText = text.substring(i + 1, closeBracket)
                        val linkUrl = text.substring(closeBracket + 2, closeParen)
                        val link = LinkAnnotation.Clickable(tag = "URL") {
                            onLinkClick(linkUrl)
                        }
                        withLink(link) {
                            withStyle(SpanStyle(
                                color = PortaTertiary,
                                textDecoration = TextDecoration.Underline
                            )) {
                                append(linkText)
                            }
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
