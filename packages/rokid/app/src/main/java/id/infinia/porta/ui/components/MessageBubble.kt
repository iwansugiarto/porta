package id.infinia.porta.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.JsonObject
import id.infinia.porta.shared.protocol.ChatMessage
import id.infinia.porta.ui.theme.*

/**
 * Renders a ChatMessage as the appropriate bubble/card type.
 *
 * - User messages: right-aligned indigo bubble (long-press to copy)
 * - Assistant messages: left-aligned with markdown (long-press context menu)
 * - System messages: compact cards (commands, diffs, info)
 */
@Composable
fun MessageBubble(
    message: ChatMessage,
    onApproveCommand: ((String, Int) -> Unit)? = null,
    onRejectCommand: ((String, Int) -> Unit)? = null,
    onApprovePermission: ((String, Int, Boolean, Int) -> Unit)? = null,
    onRevert: ((Int) -> Unit)? = null,
) {
    when (message.role) {
        "user" -> UserBubble(message)
        "assistant" -> AssistantBubble(message, onRevert)
        "system" -> SystemCard(message, onApproveCommand, onRejectCommand, onApprovePermission)
    }
}

// ── User Bubble ──

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserBubble(message: ChatMessage) {
    val clipboardManager = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    var showCopied by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 320.dp),
            shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
            color = PortaPrimary.copy(alpha = 0.15f),
            tonalElevation = 1.dp
        ) {
            Column(
                modifier = Modifier
                    .combinedClickable(
                        onClick = {},
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            clipboardManager.setText(AnnotatedString(message.content))
                            showCopied = true
                        }
                    )
                    .padding(12.dp)
            ) {
                Text(
                    message.content,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (showCopied) {
                    LaunchedEffect(Unit) {
                        kotlinx.coroutines.delay(1500)
                        showCopied = false
                    }
                    Text(
                        "✓ Copied",
                        fontSize = 10.sp,
                        color = PortaSuccess,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

// ── Assistant Bubble ──

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AssistantBubble(message: ChatMessage, onRevert: ((Int) -> Unit)? = null) {
    val clipboardManager = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    var showMenu by remember { mutableStateOf(false) }
    var showCopied by remember { mutableStateOf(false) }

    Box {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        showMenu = true
                    }
                ),
            horizontalAlignment = Alignment.Start
        ) {
            // Thinking block (collapsible)
            val thinkingText = message.thinking
            if (!thinkingText.isNullOrBlank()) {
                ThinkingBlock(thinkingText, message.thinkingDuration)
            }

            // Main response
            if (message.content.isNotBlank()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp),
                    color = Color.Transparent,
                ) {
                    MarkdownText(
                        markdown = message.content,
                        modifier = Modifier.padding(4.dp)
                    )
                }
            }

            // Copied feedback
            if (showCopied) {
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(1500)
                    showCopied = false
                }
                Text(
                    "✓ Copied",
                    fontSize = 10.sp,
                    color = PortaSuccess,
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                )
            }
        }

        // Context menu dropdown
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text("Copy", fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp)) },
                onClick = {
                    clipboardManager.setText(AnnotatedString(message.content))
                    showMenu = false
                    showCopied = true
                }
            )
            if (onRevert != null && message.stepIndex >= 0) {
                DropdownMenuItem(
                    text = { Text("Revert to here", fontSize = 13.sp) },
                    leadingIcon = {
                        Icon(Icons.Default.Undo, null, Modifier.size(16.dp), tint = PortaWarning)
                    },
                    onClick = {
                        onRevert(message.stepIndex)
                        showMenu = false
                    }
                )
            }
        }
    }
}

// ── Thinking Block ──

@Composable
private fun ThinkingBlock(thinking: String, duration: String?) {
    var expanded by remember { mutableStateOf(false) }

    val durationLabel = duration?.let {
        val match = Regex("([\\d.]+)s").find(it)
        match?.groupValues?.get(1)?.toFloatOrNull()?.let { secs ->
            String.format("%.1fs", secs)
        }
    } ?: ""

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { expanded = !expanded },
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
                Text(
                    "Thinking${if (durationLabel.isNotEmpty()) " for $durationLabel" else ""}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    fontWeight = FontWeight.Medium
                )
            }
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                Text(
                    thinking,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
    Spacer(Modifier.height(4.dp))
}

// ── System Cards ──

@Composable
private fun SystemCard(
    message: ChatMessage,
    onApproveCommand: ((String, Int) -> Unit)?,
    onRejectCommand: ((String, Int) -> Unit)?,
    onApprovePermission: ((String, Int, Boolean, Int) -> Unit)?,
) {
    when (message.type) {
        "CORTEX_STEP_TYPE_RUN_COMMAND" -> CommandCard(message, onApproveCommand, onRejectCommand)
        "CORTEX_STEP_TYPE_CODE_ACTION" -> CodeActionCard(message)
        "CORTEX_STEP_TYPE_FILE_PERMISSION" -> FilePermissionCard(message, onApprovePermission)
        else -> InfoCard(message)
    }
}

// ── Command Card ──

@Composable
private fun CommandCard(
    message: ChatMessage,
    onApprove: ((String, Int) -> Unit)?,
    onReject: ((String, Int) -> Unit)?,
) {
    val stepData = message.step ?: return
    val cmd = stepData.getAsJsonObject("runCommand") ?: return
    var expanded by remember { mutableStateOf(false) }
    var responded by remember { mutableStateOf(false) }
    var approvalChoice by remember { mutableStateOf("") } // "approved" or "rejected"

    val isWaiting = stepData.get("status")?.asString == "CORTEX_STEP_STATUS_WAITING"
    val commandLine = if (isWaiting) {
        cmd.get("proposedCommandLine")?.asString ?: cmd.get("commandLine")?.asString ?: ""
    } else {
        cmd.get("commandLine")?.asString ?: cmd.get("command")?.asString ?: ""
    }
    val output = cmd.getAsJsonObject("combinedOutput")?.get("full")?.asString
        ?: cmd.get("output")?.asString ?: ""
    val cwd = cmd.get("cwd")?.asString
    val exitCode = cmd.get("exitCode")?.asInt

    val trajectoryId = stepData.getAsJsonObject("metadata")
        ?.getAsJsonObject("sourceTrajectoryStepInfo")
        ?.get("trajectoryId")?.asString ?: ""
    val stepIdx = stepData.getAsJsonObject("metadata")
        ?.getAsJsonObject("sourceTrajectoryStepInfo")
        ?.get("stepIndex")?.asInt ?: 0

    val statusColor = when {
        isWaiting && !responded -> PortaWarning
        responded && approvalChoice == "approved" -> PortaSuccess
        responded && approvalChoice == "rejected" -> PortaError
        exitCode == null -> PortaTertiary
        exitCode == 0 -> PortaSuccess
        else -> PortaError
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = statusColor.copy(alpha = 0.06f))
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            // Header: icon + command
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = output.isNotEmpty()) { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(Icons.Default.Terminal, null, Modifier.size(13.dp), tint = statusColor)
                Text(
                    "$ $commandLine",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
                )
                if (exitCode != null) {
                    Text(
                        if (exitCode == 0) "✓" else "✗ $exitCode",
                        fontSize = 11.sp,
                        color = statusColor
                    )
                }
                if (output.isNotEmpty()) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        null, Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            }

            // CWD
            if (cwd != null) {
                Text(cwd, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
            }

            // Waiting for approval — with instant feedback
            if (isWaiting && onApprove != null && onReject != null) {
                Spacer(Modifier.height(8.dp))
                if (!responded) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(PortaWarning))
                            Text("Waiting for approval", fontSize = 11.sp, color = PortaWarning)
                        }
                        OutlinedButton(
                            onClick = {
                                responded = true
                                approvalChoice = "rejected"
                                onReject(trajectoryId, stepIdx)
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = PortaError)
                        ) { Text("Reject", fontSize = 11.sp) }
                        Button(
                            onClick = {
                                responded = true
                                approvalChoice = "approved"
                                onApprove(trajectoryId, stepIdx)
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = PortaSuccess)
                        ) { Text("Approve", fontSize = 11.sp) }
                    }
                } else {
                    // Instant feedback after responding
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            if (approvalChoice == "approved") Icons.Default.CheckCircle else Icons.Default.Cancel,
                            null, Modifier.size(14.dp),
                            tint = if (approvalChoice == "approved") PortaSuccess else PortaError
                        )
                        Text(
                            if (approvalChoice == "approved") "Approved" else "Rejected",
                            fontSize = 11.sp, fontWeight = FontWeight.Medium,
                            color = if (approvalChoice == "approved") PortaSuccess else PortaError
                        )
                    }
                }
            }

            // Expanded output
            if (expanded && output.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    output,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(6.dp))
                        .padding(8.dp)
                )
            }
        }
    }
}

// ── Code Action Card ──

@Composable
private fun CodeActionCard(message: ChatMessage) {
    val stepData2 = message.step ?: return
    val ca = stepData2.getAsJsonObject("codeAction") ?: return
    var expanded by remember { mutableStateOf(false) }

    val description = ca.get("description")?.asString ?: "Code change"
    val edit = ca.getAsJsonObject("actionResult")?.getAsJsonObject("edit")
    val fileUri = edit?.get("absoluteUri")?.asString ?: ""
    val fileName = fileUri.substringAfterLast("/")

    val diffLines = edit?.getAsJsonObject("diff")
        ?.getAsJsonObject("unifiedDiff")
        ?.getAsJsonArray("lines")

    val additions = diffLines?.count {
        it.asJsonObject.get("type")?.asString == "UNIFIED_DIFF_LINE_TYPE_INSERT"
    } ?: 0
    val deletions = diffLines?.count {
        it.asJsonObject.get("type")?.asString == "UNIFIED_DIFF_LINE_TYPE_DELETE"
    } ?: 0
    val hasDiff = (diffLines?.size() ?: 0) > 0

    val toolName = stepData2.getAsJsonObject("metadata")
        ?.getAsJsonObject("toolCall")?.get("name")?.asString ?: ""
    val icon = when (toolName) {
        "write_to_file" -> Icons.Default.Add
        "multi_replace_file_content", "replace_file_content" -> Icons.Default.Edit
        else -> Icons.Default.Description
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = hasDiff) { expanded = !expanded },
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(icon, null, Modifier.size(13.dp), tint = PortaTertiary)
                // Diff stats
                Text("+$additions", fontSize = 11.sp, color = PortaSuccess, fontWeight = FontWeight.Bold)
                Text("-$deletions", fontSize = 11.sp, color = PortaError, fontWeight = FontWeight.Bold)
                if (fileName.isNotEmpty()) {
                    Text(
                        fileName, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                        color = PortaTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
                Spacer(Modifier.weight(1f))
                if (hasDiff) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        null, Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            }
            // Description
            Text(
                description, fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )

            // Expanded diff view
            if (expanded && hasDiff && diffLines != null) {
                Spacer(Modifier.height(6.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(6.dp))
                        .padding(6.dp)
                ) {
                    if (fileUri.isNotEmpty()) {
                        Text(
                            fileUri.removePrefix("file://"),
                            fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    for (line in diffLines) {
                        val lineObj = line.asJsonObject
                        val lineType = lineObj.get("type")?.asString ?: ""
                        val lineText = lineObj.get("text")?.asString ?: ""
                        val (prefix, textColor) = when (lineType) {
                            "UNIFIED_DIFF_LINE_TYPE_INSERT" -> "+" to PortaSuccess
                            "UNIFIED_DIFF_LINE_TYPE_DELETE" -> "-" to PortaError
                            "UNIFIED_DIFF_LINE_TYPE_HUNK_HEADER" -> "@@" to PortaTertiary
                            else -> " " to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        }
                        val bgColor = when (lineType) {
                            "UNIFIED_DIFF_LINE_TYPE_INSERT" -> PortaSuccess.copy(alpha = 0.08f)
                            "UNIFIED_DIFF_LINE_TYPE_DELETE" -> PortaError.copy(alpha = 0.08f)
                            else -> Color.Transparent
                        }
                        Text(
                            "$prefix $lineText",
                            fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                            lineHeight = 14.sp, color = textColor,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(bgColor)
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
            }
        }
    }
}

// ── File Permission Card ──

@Composable
private fun FilePermissionCard(
    message: ChatMessage,
    onPermission: ((String, Int, Boolean, Int) -> Unit)?,
) {
    val step = message.step ?: return
    val fpr = step.getAsJsonObject("filePermissionRequest")
        ?: step.getAsJsonObject("viewFile")?.getAsJsonObject("filePermissionRequest")
        ?: step.getAsJsonObject("codeAction")?.getAsJsonObject("filePermissionRequest")
        ?: return

    var responded by remember { mutableStateOf(false) }
    val isWaiting = step.get("status")?.asString == "CORTEX_STEP_STATUS_WAITING"

    val path = fpr.get("absolutePathUri")?.asString ?: ""
    val displayPath = if (path.length > 50) "…${path.takeLast(45)}" else path
    val isDir = fpr.get("isDirectory")?.asBoolean ?: false
    val blockReason = fpr.get("blockReason")?.asString

    val trajectoryId = step.getAsJsonObject("metadata")
        ?.getAsJsonObject("sourceTrajectoryStepInfo")
        ?.get("trajectoryId")?.asString ?: ""
    val stepIdx = step.getAsJsonObject("metadata")
        ?.getAsJsonObject("sourceTrajectoryStepInfo")
        ?.get("stepIndex")?.asInt ?: 0

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isWaiting && !responded) PortaWarning.copy(alpha = 0.08f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(Icons.Default.Lock, null, Modifier.size(13.dp), tint = PortaWarning)
                Text(
                    "File access: ",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Text(
                    "$displayPath${if (isDir) " (dir)" else ""}",
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    color = PortaTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            if (blockReason != null) {
                Text(
                    blockReason.removePrefix("BLOCK_REASON_").replace("_", " ").lowercase(),
                    fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }
            if (isWaiting && !responded && onPermission != null) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)
                ) {
                    OutlinedButton(
                        onClick = { responded = true; onPermission(trajectoryId, stepIdx, false, 0) },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = PortaError)
                    ) { Text("Deny", fontSize = 11.sp) }
                    FilledTonalButton(
                        onClick = { responded = true; onPermission(trajectoryId, stepIdx, true, 1) },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                    ) { Text("Once", fontSize = 11.sp) }
                    Button(
                        onClick = { responded = true; onPermission(trajectoryId, stepIdx, true, 2) },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PortaSuccess)
                    ) { Text("Allow Conv.", fontSize = 11.sp) }
                }
            }
        }
    }
}

// ── Info Card (grep, view, list, find) ──

@Composable
private fun InfoCard(message: ChatMessage) {
    val icon = when (message.icon) {
        "search" -> Icons.Default.Search
        "eye" -> Icons.Default.Visibility
        "folder" -> Icons.Default.Folder
        "list" -> Icons.Default.ViewList
        "file-search" -> Icons.Default.FindInPage
        else -> Icons.Default.Info
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            icon, null, Modifier.size(12.dp),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
        )
        // Simple inline markdown-like rendering for info cards
        val text = message.content
            .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")  // strip bold markers for display
            .replace(Regex("`(.+?)`"), "$1")              // strip code markers
        Text(
            text,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
