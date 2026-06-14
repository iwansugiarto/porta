package id.infinia.porta.ui.components

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.google.gson.JsonObject
import id.infinia.porta.shared.protocol.ChatMessage
import id.infinia.porta.shared.protocol.QuestionInfo
import id.infinia.porta.shared.protocol.QuestionOption
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
    onApprovePermission: ((String, Int, Boolean, Int, String) -> Unit)? = null,
    onRevert: ((Int) -> Unit)? = null,
    onAnswerQuestion: ((String, Int, List<Int>, String?) -> Unit)? = null,
    onSendFeedback: ((String) -> Unit)? = null,
) {
    when (message.role) {
        "user" -> UserBubble(message)
        "assistant" -> AssistantBubble(message, onRevert, onSendFeedback)
        "system" -> SystemCard(message, onApproveCommand, onRejectCommand, onApprovePermission, onAnswerQuestion)
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
            color = PortaPrimary.copy(alpha = if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) 0.10f else 0.15f),
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
                // Media thumbnails
                val mediaList = message.media
                if (!mediaList.isNullOrEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(bottom = if (message.content.isNotBlank()) 8.dp else 0.dp)
                    ) {
                        mediaList.take(4).forEach { mediaObj ->
                            val mimeType = (mediaObj as? JsonObject)?.get("mimeType")?.asString ?: ""
                            val inlineData = (mediaObj as? JsonObject)?.get("inlineData")?.asString
                            if (mimeType.startsWith("image/") && inlineData != null) {
                                val bytes = Base64.decode(inlineData, Base64.NO_WRAP)
                                val bmp = remember(inlineData.hashCode()) {
                                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                }
                                if (bmp != null) {
                                    Image(
                                        bitmap = bmp.asImageBitmap(),
                                        contentDescription = "Attachment",
                                        modifier = Modifier
                                            .size(64.dp)
                                            .clip(RoundedCornerShape(8.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                            }
                        }
                    }
                }
                if (message.content.isNotBlank()) {
                    Text(
                        message.content,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
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
                // Timestamp
                message.timestamp?.let { ts ->
                    val relative = formatRelativeTime(ts)
                    if (relative.isNotEmpty()) {
                        Text(
                            relative,
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

// ── Assistant Bubble ──

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AssistantBubble(
    message: ChatMessage,
    onRevert: ((Int) -> Unit)? = null,
    onSendFeedback: ((String) -> Unit)? = null,
) {
    val clipboardManager = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    var showMenu by remember { mutableStateOf(false) }
    var showCopied by remember { mutableStateOf(false) }

    // ── Plan annotation state ──
    val isPlan = remember(message.content) {
        message.content.contains("## Proposed Changes") ||
        message.content.contains("## Open Questions") ||
        message.content.contains("## User Review Required")
    }
    val annotations = remember { mutableStateMapOf<Int, String>() }
    var showAnnotationDialog by remember { mutableStateOf(false) }
    var annotationBlockIndex by remember { mutableIntStateOf(-1) }
    var annotationDraft by remember { mutableStateOf("") }

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
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(
                        alpha = if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) 0.6f else 0f
                    ),
                ) {
                    MarkdownText(
                        markdown = message.content,
                        modifier = Modifier.padding(4.dp),
                        annotatable = isPlan,
                        annotations = annotations,
                        onAnnotationRequested = { blockIndex ->
                            annotationBlockIndex = blockIndex
                            annotationDraft = annotations[blockIndex] ?: ""
                            showAnnotationDialog = true
                        }
                    )
                }
            }

            // Submit Feedback bar for annotated plans
            if (isPlan && annotations.isNotEmpty() && onSendFeedback != null) {
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = {
                        val feedback = buildAnnotationFeedback(annotations, message.content)
                        onSendFeedback(feedback)
                        annotations.clear()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = PortaPrimary),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.RateReview, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Submit Feedback (${annotations.size} comment${if (annotations.size != 1) "s" else ""})",
                        fontSize = 12.sp
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

            // Timestamp
            message.timestamp?.let { ts ->
                val relative = formatRelativeTime(ts)
                if (relative.isNotEmpty()) {
                    Text(
                        relative,
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                        modifier = Modifier.padding(start = 4.dp, top = 1.dp)
                    )
                }
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
                        Icon(Icons.AutoMirrored.Filled.Undo, null, Modifier.size(16.dp), tint = PortaWarning)
                    },
                    onClick = {
                        onRevert(message.stepIndex)
                        showMenu = false
                    }
                )
            }
        }
    }

    // ── Annotation Dialog ──
    if (showAnnotationDialog) {
        Dialog(onDismissRequest = { showAnnotationDialog = false }) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Add Comment",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = annotationDraft,
                        onValueChange = { annotationDraft = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                        placeholder = { Text("Your feedback on this section…", fontSize = 13.sp) },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                    ) {
                        if (annotations.containsKey(annotationBlockIndex)) {
                            TextButton(onClick = {
                                annotations.remove(annotationBlockIndex)
                                showAnnotationDialog = false
                            }) {
                                Text("Remove", fontSize = 12.sp, color = PortaError)
                            }
                        }
                        TextButton(onClick = { showAnnotationDialog = false }) {
                            Text("Cancel", fontSize = 12.sp)
                        }
                        Button(
                            onClick = {
                                if (annotationDraft.isNotBlank()) {
                                    annotations[annotationBlockIndex] = annotationDraft
                                }
                                showAnnotationDialog = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = PortaPrimary)
                        ) {
                            Text("Save", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

// ── Thinking Block ──

@Composable
private fun ThinkingBlock(thinking: String, duration: String?) {
    var expanded by remember { mutableStateOf(false) }

    val durationLabel = duration?.let { d ->
        // Try to parse "Xs" format, or "Xms", or plain number
        val secMatch = Regex("([\\d.]+)\\s*s").find(d)
        val msMatch = Regex("([\\d.]+)\\s*ms").find(d)
        when {
            msMatch != null -> msMatch.groupValues[1].toFloatOrNull()?.let {
                String.format("%.1fs", it / 1000f)
            }
            secMatch != null -> secMatch.groupValues[1].toFloatOrNull()?.let {
                String.format("%.1fs", it)
            }
            else -> d.toFloatOrNull()?.let { String.format("%.1fs", it) }
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
    onApprovePermission: ((String, Int, Boolean, Int, String) -> Unit)?,
    onAnswerQuestion: ((String, Int, List<Int>, String?) -> Unit)?,
) {
    when (message.type) {
        "CORTEX_STEP_TYPE_RUN_COMMAND" -> CommandCard(message, onApproveCommand, onRejectCommand)
        "CORTEX_STEP_TYPE_CODE_ACTION" -> CodeActionCard(message)
        "CORTEX_STEP_TYPE_FILE_PERMISSION" -> FilePermissionCard(message, onApprovePermission)
        "ASK_QUESTION" -> QuestionCard(message, onAnswerQuestion)
        "CORTEX_STEP_TYPE_INVOKE_SUBAGENT" -> SubagentCard(message)
        "CORTEX_STEP_TYPE_GENERATE_IMAGE" -> GeneratedImageCard(message)
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

    val isRunningCmd = stepData.get("status")?.asString?.let {
        it == "CORTEX_STEP_STATUS_RUNNING" || it == "CORTEX_STEP_STATUS_PENDING"
    } ?: false

    val statusColor = when {
        isWaiting && !responded -> PortaWarning
        responded && approvalChoice == "approved" -> PortaSuccess
        responded && approvalChoice == "rejected" -> PortaError
        isRunningCmd -> PortaTertiary
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
                // Pulsing dot for running commands
                if (isRunningCmd) {
                    val infiniteTransition = rememberInfiniteTransition(label = "cmdPulse")
                    val alpha by infiniteTransition.animateFloat(
                        initialValue = 1f,
                        targetValue = 0.3f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(800, easing = EaseInOutSine),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "cmdPulseAlpha"
                    )
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(PortaTertiary.copy(alpha = alpha))
                    )
                }
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
                if (isRunningCmd) {
                    Text(
                        "running",
                        fontSize = 10.sp,
                        color = PortaTertiary.copy(alpha = 0.7f)
                    )
                } else if (exitCode != null) {
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

            // Expanded output (max height with scroll for very long outputs)
            if (expanded && output.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                val scrollState = rememberScrollState()
                val displayOutput = if (output.length > 8000) {
                    output.take(8000) + "\n…(truncated, ${output.length} chars total)"
                } else output
                Text(
                    displayOutput,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .verticalScroll(scrollState)
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

    // ── Artifact detection ──
    val toolArgs = stepData2.getAsJsonObject("metadata")
        ?.getAsJsonObject("toolCall")?.getAsJsonObject("arguments")
    val isArtifact = toolArgs?.get("IsArtifact")?.asBoolean
        ?: toolArgs?.get("isArtifact")?.asBoolean ?: false
    val artifactMeta = toolArgs?.getAsJsonObject("ArtifactMetadata")
        ?: toolArgs?.getAsJsonObject("artifactMetadata")
    val artifactType = artifactMeta?.get("ArtifactType")?.asString
        ?: artifactMeta?.get("artifactType")?.asString
    val artifactSummary = artifactMeta?.get("Summary")?.asString
        ?: artifactMeta?.get("summary")?.asString

    val icon = when {
        isArtifact -> Icons.Default.Description
        toolName == "write_to_file" -> Icons.Default.Add
        toolName == "multi_replace_file_content" || toolName == "replace_file_content" -> Icons.Default.Edit
        else -> Icons.Default.Description
    }

    val accentAmber = Color(0xFFFBBF24)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = hasDiff || isArtifact) { expanded = !expanded },
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(icon, null, Modifier.size(13.dp),
                    tint = if (isArtifact) accentAmber else PortaTertiary)

                // Artifact badge
                if (isArtifact) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = accentAmber.copy(alpha = 0.15f)
                    ) {
                        Text(
                            "ARTIFACT",
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            color = accentAmber,
                            letterSpacing = 0.5.sp,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                }

                // Diff stats
                if (!isArtifact) {
                    Text("+$additions", fontSize = 11.sp, color = PortaSuccess, fontWeight = FontWeight.Bold)
                    Text("-$deletions", fontSize = 11.sp, color = PortaError, fontWeight = FontWeight.Bold)
                }
                if (fileName.isNotEmpty()) {
                    Text(
                        fileName, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                        color = if (isArtifact) accentAmber else PortaTertiary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }

                // Artifact type badge
                if (isArtifact && artifactType != null) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            artifactType.replace("_", " "),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                }

                Spacer(Modifier.weight(1f))
                if (hasDiff || isArtifact) {
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
                maxLines = if (isArtifact) 2 else 1, overflow = TextOverflow.Ellipsis
            )

            // Artifact summary (expanded)
            if (expanded && isArtifact && !artifactSummary.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(6.dp),
                    color = accentAmber.copy(alpha = 0.04f)
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            "Summary",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = accentAmber.copy(alpha = 0.7f),
                            letterSpacing = 0.5.sp
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            artifactSummary,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
            }

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
    onPermission: ((String, Int, Boolean, Int, String) -> Unit)?,
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
                        onClick = { responded = true; onPermission(trajectoryId, stepIdx, false, 0, path) },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = PortaError)
                    ) { Text("Deny", fontSize = 11.sp) }
                    FilledTonalButton(
                        onClick = { responded = true; onPermission(trajectoryId, stepIdx, true, 1, path) },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                    ) { Text("Once", fontSize = 11.sp) }
                    Button(
                        onClick = { responded = true; onPermission(trajectoryId, stepIdx, true, 2, path) },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PortaSuccess)
                    ) { Text("Allow Conv.", fontSize = 11.sp) }
                }
            }
        }
    }
}

// ── Generated Image Card ──

@Composable
private fun GeneratedImageCard(message: ChatMessage) {
    val step = message.step ?: return
    val gi = step.getAsJsonObject("generateImage") ?: return
    val prompt = gi.get("prompt")?.asString ?: ""
    val imageName = gi.get("imageName")?.asString ?: gi.get("ImageName")?.asString ?: "image"
    var expanded by remember { mutableStateOf(false) }

    val accentGreen = Color(0xFF34D399)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = accentGreen.copy(alpha = 0.06f)
        )
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Image icon with gradient background
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    Color(0xFF6366F1),
                                    Color(0xFF8B5CF6),
                                    Color(0xFFEC4899)
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Image,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = Color.White
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Generated: $imageName",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "🎨 AI-generated image",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }

                // Expand chevron
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    null, Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }

            // Expanded: show prompt
            if (expanded && prompt.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            "Prompt",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                            letterSpacing = 0.5.sp
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            prompt,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                        )
                    }
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
        "list" -> Icons.AutoMirrored.Filled.ViewList
        "file-search" -> Icons.Default.FindInPage
        "image" -> Icons.Default.Image
        "info" -> Icons.Default.Info
        "agents" -> Icons.Default.People
        "message" -> Icons.AutoMirrored.Filled.Chat
        "clock" -> Icons.Default.Schedule
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

// ── Question Card ──

@Composable
private fun QuestionCard(
    message: ChatMessage,
    onAnswer: ((String, Int, List<Int>, String?) -> Unit)?,
) {
    val step = message.step ?: return
    val interaction = step.getAsJsonObject("requestedInteraction") ?: return
    val aq = interaction.getAsJsonObject("askQuestion")
        ?: interaction.getAsJsonObject("AskQuestion")
        ?: return

    // Parse question data
    val questionsArr = aq.getAsJsonArray("questions")
    val firstQ = questionsArr?.firstOrNull()?.asJsonObject ?: aq
    val questionText = firstQ.get("question")?.asString ?: "Select an option"
    val isMultiSelect = firstQ.get("is_multi_select")?.asBoolean
        ?: firstQ.get("isMultiSelect")?.asBoolean
        ?: false
    val optionsArr = firstQ.getAsJsonArray("options") ?: aq.getAsJsonArray("options")
    val options = mutableListOf<String>()
    optionsArr?.forEach { opt ->
        val text = if (opt.isJsonPrimitive) opt.asString
        else opt.asJsonObject?.get("text")?.asString ?: opt.toString()
        options.add(text)
    }

    val trajectoryId = step.getAsJsonObject("metadata")
        ?.getAsJsonObject("sourceTrajectoryStepInfo")
        ?.get("trajectoryId")?.asString ?: ""
    val stepIdx = step.getAsJsonObject("metadata")
        ?.getAsJsonObject("sourceTrajectoryStepInfo")
        ?.get("stepIndex")?.asInt ?: 0

    var responded by remember { mutableStateOf(false) }
    val selectedIndices = remember { mutableStateListOf<Int>() }
    var writeInText by remember { mutableStateOf("") }
    var submittedSummary by remember { mutableStateOf("") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (!responded) PortaPrimary.copy(alpha = 0.08f)
            else PortaSuccess.copy(alpha = 0.06f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("❓", fontSize = 14.sp)
                Text(
                    "Question",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = PortaPrimary
                )
                if (isMultiSelect) {
                    Text(
                        "(select all that apply)",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            // Question text
            Text(
                questionText,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
            )

            Spacer(Modifier.height(8.dp))

            if (!responded) {
                // Options
                options.forEachIndexed { idx, optionText ->
                    val isSelected = idx in selectedIndices
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                if (isMultiSelect) {
                                    if (isSelected) selectedIndices.remove(idx)
                                    else selectedIndices.add(idx)
                                } else {
                                    selectedIndices.clear()
                                    selectedIndices.add(idx)
                                }
                            },
                        color = if (isSelected) PortaPrimary.copy(alpha = 0.12f)
                        else MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (isMultiSelect) {
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = {
                                        if (it) selectedIndices.add(idx)
                                        else selectedIndices.remove(idx)
                                    },
                                    modifier = Modifier.size(18.dp),
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = PortaPrimary,
                                        uncheckedColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                    )
                                )
                            } else {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = {
                                        selectedIndices.clear()
                                        selectedIndices.add(idx)
                                    },
                                    modifier = Modifier.size(18.dp),
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = PortaPrimary,
                                        unselectedColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                    )
                                )
                            }
                            Text(
                                optionText,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(
                                    alpha = if (isSelected) 1f else 0.7f
                                )
                            )
                        }
                    }
                }

                // Write-in field
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = writeInText,
                    onValueChange = { writeInText = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Write your own answer…", fontSize = 12.sp) },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    singleLine = false,
                    maxLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PortaPrimary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
                    )
                )

                Spacer(Modifier.height(8.dp))

                // Submit / Skip buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    OutlinedButton(
                        onClick = {
                            responded = true
                            submittedSummary = "Skipped"
                            onAnswer?.invoke(trajectoryId, stepIdx, emptyList(), null)
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                    ) { Text("Skip", fontSize = 11.sp) }

                    Button(
                        onClick = {
                            responded = true
                            val selections = selectedIndices.sorted()
                            val summary = selections.map { options.getOrElse(it) { "?" } }
                                .joinToString(", ")
                            submittedSummary = if (summary.isNotEmpty()) summary
                            else writeInText.take(50).ifEmpty { "Submitted" }
                            onAnswer?.invoke(
                                trajectoryId, stepIdx,
                                selections,
                                writeInText.ifBlank { null }
                            )
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PortaPrimary),
                        enabled = selectedIndices.isNotEmpty() || writeInText.isNotBlank()
                    ) { Text("Submit", fontSize = 11.sp) }
                }
            } else {
                // Post-submit summary
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(Icons.Default.CheckCircle, null, Modifier.size(14.dp), tint = PortaSuccess)
                    Text(
                        submittedSummary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = PortaSuccess,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

// ── Subagent Card ──

@Composable
private fun SubagentCard(message: ChatMessage) {
    val step = message.step ?: return
    val invokeData = step.getAsJsonObject("invokeSubagent") ?: return

    val subagentsArr = invokeData.getAsJsonArray("Subagents")
        ?: invokeData.getAsJsonArray("subagents")
    val count = subagentsArr?.size() ?: 0

    data class SubagentInfo(val typeName: String, val role: String)
    val subagents = mutableListOf<SubagentInfo>()
    subagentsArr?.forEach { sa ->
        val obj = sa.asJsonObject ?: return@forEach
        val type = obj.get("TypeName")?.asString
            ?: obj.get("typeName")?.asString ?: "agent"
        val role = obj.get("Role")?.asString
            ?: obj.get("role")?.asString ?: "Subagent"
        subagents.add(SubagentInfo(type, role))
    }

    val status = step.get("status")?.asString
    val isRunning = status == "CORTEX_STEP_STATUS_RUNNING" ||
        status == "CORTEX_STEP_STATUS_PENDING"
    val isDone = status == "CORTEX_STEP_STATUS_COMPLETE"

    var expanded by remember { mutableStateOf(false) }

    val accentPurple = Color(0xFFA78BFA)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isDone) PortaSuccess.copy(alpha = 0.06f)
            else accentPurple.copy(alpha = 0.08f)
        )
    ) {
        Column {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { if (subagents.isNotEmpty()) expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Agent icon
                Text("🤖", fontSize = 13.sp)

                // Status indicator
                if (isRunning) {
                    // Animated spinning indicator
                    val infiniteTransition = rememberInfiniteTransition(label = "subagentSpin")
                    val rotation by infiniteTransition.animateFloat(
                        initialValue = 0f,
                        targetValue = 360f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1200, easing = LinearEasing)
                        ),
                        label = "subagentSpinRotation"
                    )
                    val pulseAlpha by infiniteTransition.animateFloat(
                        initialValue = 1f,
                        targetValue = 0.4f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(800, easing = EaseInOutSine),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "subagentPulse"
                    )
                    Icon(
                        Icons.Default.Sync,
                        contentDescription = "Running",
                        modifier = Modifier
                            .size(14.dp)
                            .rotate(rotation)
                            .padding(end = 2.dp),
                        tint = accentPurple.copy(alpha = pulseAlpha)
                    )
                } else if (isDone) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Done",
                        modifier = Modifier
                            .size(14.dp)
                            .padding(end = 2.dp),
                        tint = PortaSuccess
                    )
                }

                Text(
                    "Launched $count subagent${if (count != 1) "s" else ""}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.weight(1f)
                )

                if (subagents.isNotEmpty()) {
                    Text(
                        if (expanded) "▴" else "▾",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            }

            // Expanded role list
            if (expanded && subagents.isNotEmpty()) {
                Column(
                    modifier = Modifier.padding(
                        start = 32.dp, end = 12.dp, bottom = 10.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    subagents.forEach { sa ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Type badge
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = accentPurple
                            ) {
                                Text(
                                    sa.typeName.uppercase(),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White,
                                    modifier = Modifier.padding(
                                        horizontal = 6.dp, vertical = 1.dp
                                    )
                                )
                            }
                            // Role
                            Text(
                                sa.role,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface.copy(
                                    alpha = 0.8f
                                ),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Annotation Helpers ──

/**
 * Build a structured markdown feedback message from annotations on a plan.
 * Extracts the text surrounding each annotated block index for context.
 */
private fun buildAnnotationFeedback(
    annotations: Map<Int, String>,
    planMarkdown: String
): String {
    val lines = planMarkdown.lines()
    // Split into blocks by blank lines (rough block segmentation)
    val blocks = mutableListOf<String>()
    val current = StringBuilder()
    for (line in lines) {
        if (line.isBlank() && current.isNotEmpty()) {
            blocks.add(current.toString().trim())
            current.clear()
        } else {
            current.appendLine(line)
        }
    }
    if (current.isNotEmpty()) blocks.add(current.toString().trim())

    val sb = StringBuilder()
    sb.appendLine("## Feedback on Implementation Plan\n")

    for ((blockIdx, comment) in annotations.toSortedMap()) {
        val blockText = blocks.getOrNull(blockIdx) ?: "(section $blockIdx)"
        // Take first 2 lines as excerpt
        val excerpt = blockText.lines().take(2).joinToString("\n")
        sb.appendLine("### Block ${blockIdx + 1}")
        sb.appendLine("> ${excerpt.replace("\n", "\n> ")}")
        sb.appendLine()
        sb.appendLine("💬 $comment")
        sb.appendLine()
        sb.appendLine("---\n")
    }

    return sb.toString().trim()
}

/**
 * Format an ISO-8601 timestamp string as relative time (e.g. "2m ago", "1h ago").
 * Returns empty string if parsing fails.
 */
private fun formatRelativeTime(isoTimestamp: String): String {
    return try {
        val instant = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            java.time.Instant.parse(isoTimestamp).toEpochMilli()
        } else {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
            sdf.parse(isoTimestamp.replace(Regex("\\.[0-9]+Z$"), "Z").replace("Z", ""))?.time
                ?: return ""
        }

        val now = System.currentTimeMillis()
        val diff = now - instant
        when {
            diff < 0 -> ""
            diff < 60_000 -> "just now"
            diff < 3_600_000 -> "${diff / 60_000}m ago"
            diff < 86_400_000 -> "${diff / 3_600_000}h ago"
            diff < 604_800_000 -> "${diff / 86_400_000}d ago"
            else -> {
                val sdf = java.text.SimpleDateFormat("MMM d", java.util.Locale.US)
                sdf.format(java.util.Date(instant))
            }
        }
    } catch (_: Exception) { "" }
}
