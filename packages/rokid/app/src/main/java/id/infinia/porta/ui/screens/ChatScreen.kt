package id.infinia.porta.ui.screens

import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import kotlinx.coroutines.launch
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import id.infinia.porta.SharedContent
import id.infinia.porta.shared.protocol.*
import id.infinia.porta.ui.components.ConversationSwitcherBar
import id.infinia.porta.ui.components.MessageBubble
import id.infinia.porta.ui.theme.*
import id.infinia.porta.viewmodel.BridgeViewModel
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Main chat screen — full UX parity with the Porta PWA.
 *
 * Features:
 * - Chat messages (user/assistant/system) in bubble layout
 * - System cards for commands, diffs, and file permissions
 * - Voice input with live transcription + TTS toggle
 * - Model selector dropdown
 * - Planner mode toggle (Fast / Plan)
 * - Stop generation button
 * - Connection status
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: BridgeViewModel,
    onNavigateToSettings: () -> Unit,
    onNavigateToConversations: () -> Unit,
    sharedContent: SharedContent? = null,
    onSharedContentConsumed: () -> Unit = {}
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val agentRunning by viewModel.agentRunning.collectAsState()
    val chatMessages by viewModel.chatMessages.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val currentConversationId by viewModel.currentConversationId.collectAsState()
    val pendingApprovals by viewModel.pendingApprovals.collectAsState()
    val conversations by viewModel.conversations.collectAsState()

    val isListening by viewModel.isListening.collectAsState()
    val partialVoice by viewModel.voiceInput.partialResult.collectAsState()
    val ttsEnabled by viewModel.ttsEnabled.collectAsState()
    val volumeLevel by viewModel.voiceInput.volumeLevel.collectAsState()

    // Model & planner state
    val availableModels by viewModel.availableModels.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()
    val defaultModel by viewModel.defaultModel.collectAsState()
    val plannerType by viewModel.plannerType.collectAsState()

    // Derive workspace name and conversation title from active conversation
    val activeConvoSummary = currentConversationId?.let { conversations[it] }
    val workspaceName = remember(activeConvoSummary) {
        activeConvoSummary?.let { summary ->
            val workspaces = summary.getAsJsonArray("workspaces")
            if (workspaces != null && workspaces.size() > 0) {
                val ws = workspaces[0].asJsonObject
                ws.getAsJsonObject("repository")?.get("computedName")?.asString
                    ?.substringAfterLast("/")
                    ?: ws.get("workspaceFolderAbsoluteUri")?.asString
                        ?.substringAfterLast("/")
            } else null
        }
    }
    val conversationTitle = remember(activeConvoSummary) {
        activeConvoSummary?.get("summary")?.asString
    }

    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Model selector expanded state
    var modelDropdownOpen by remember { mutableStateOf(false) }

    // ── Attachment state ──
    data class Attachment(
        val uri: Uri,
        val mimeType: String,
        val base64: String,
        val fileName: String
    )
    val attachments = remember { mutableStateListOf<Attachment>() }
    val context = LocalContext.current

    // Helper to add a URI as an attachment
    fun addUriAsAttachment(uri: Uri) {
        try {
            val resolver = context.contentResolver
            val mime = resolver.getType(uri) ?: "image/png"
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return

            val finalBytes = if (mime.startsWith("image/") && bytes.size > 1_000_000) {
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
                val bos = ByteArrayOutputStream()
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, bos)
                bitmap.recycle()
                bos.toByteArray()
            } else bytes

            val b64 = Base64.encodeToString(finalBytes, Base64.NO_WRAP)
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
            attachments.add(Attachment(uri, mime, b64, name))
        } catch (_: Exception) { /* skip failed reads */ }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        uris.forEach { addUriAsAttachment(it) }
    }

    // Camera capture
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            cameraUri?.let { addUriAsAttachment(it) }
        }
    }

    // Handle shared content from external apps
    LaunchedEffect(sharedContent) {
        if (sharedContent != null) {
            sharedContent.text?.let { inputText = it }
            sharedContent.imageUris.forEach { addUriAsAttachment(it) }
            onSharedContentConsumed()
        }
    }

    // Show partial voice transcription in the input field
    val displayText = if (isListening && partialVoice.isNotBlank()) partialVoice else inputText

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(chatMessages.size) {
        if (chatMessages.isNotEmpty()) {
            listState.scrollToItem(chatMessages.size - 1)
        }
    }

    // Load models when connected
    LaunchedEffect(connectionState) {
        if (connectionState == ConnectionState.CONNECTED) {
            viewModel.loadModels()
        }
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "Porta",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        // Workspace / conversation subtitle
                        if (workspaceName != null || conversationTitle != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (workspaceName != null) {
                                    Icon(
                                        Icons.Default.Folder,
                                        contentDescription = null,
                                        modifier = Modifier.size(11.dp),
                                        tint = PortaTertiary
                                    )
                                    Text(
                                        workspaceName,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = PortaTertiary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (workspaceName != null && conversationTitle != null) {
                                    Text("›", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
                                }
                                if (conversationTitle != null) {
                                    Text(
                                        conversationTitle,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                }
                            }
                        }
                        // Connection status line
                        Text(
                            statusMessage,
                            fontSize = 11.sp,
                            color = when (connectionState) {
                                ConnectionState.CONNECTED -> PortaSuccess
                                ConnectionState.ERROR -> PortaError
                                ConnectionState.RECONNECTING -> PortaWarning
                                else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            }
                        )
                    }
                },
                actions = {
                    // TTS toggle
                    IconButton(onClick = { viewModel.toggleTts() }) {
                        Icon(
                            if (ttsEnabled) Icons.AutoMirrored.Filled.VolumeUp
                            else Icons.AutoMirrored.Filled.VolumeOff,
                            "TTS ${if (ttsEnabled) "On" else "Off"}",
                            tint = if (ttsEnabled) PortaTertiary
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                    // Stop button (when agent is running)
                    if (agentRunning) {
                        FilledIconButton(
                            onClick = { viewModel.stopGeneration() },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = PortaError.copy(alpha = 0.15f)
                            ),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Default.Stop, "Stop",
                                tint = PortaError,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    IconButton(onClick = onNavigateToConversations) {
                        Icon(Icons.Default.Forum, "Conversations")
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 8.dp
            ) {
                Column {
                    // Voice listening indicator
                    if (isListening) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(PortaPrimary.copy(alpha = 0.1f))
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size((8 + volumeLevel * 12).dp)
                                    .clip(CircleShape)
                                    .background(PortaError)
                            )
                            Text(
                                if (partialVoice.isNotBlank()) "\"$partialVoice\"" else "Listening...",
                                fontSize = 13.sp,
                                color = PortaPrimary,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { viewModel.cancelVoiceInput() }) {
                                Text("Cancel", color = PortaError, fontSize = 12.sp)
                            }
                        }
                    }

                    // Model selector + Planner mode bar
                    if (currentConversationId != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // Model selector
                            Box {
                                val activeModel = selectedModel ?: defaultModel
                                val activeLabel = availableModels
                                    .find { it.id == activeModel }?.label ?: "Model"

                                FilterChip(
                                    selected = false,
                                    onClick = {
                                        if (availableModels.isEmpty()) viewModel.loadModels()
                                        modelDropdownOpen = true
                                    },
                                    label = {
                                        Text(
                                            activeLabel,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.SmartToy, null,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    },
                                    trailingIcon = {
                                        Text("▾", fontSize = 10.sp)
                                    },
                                    modifier = Modifier.height(28.dp)
                                )

                                DropdownMenu(
                                    expanded = modelDropdownOpen,
                                    onDismissRequest = { modelDropdownOpen = false }
                                ) {
                                    availableModels.forEach { model ->
                                        DropdownMenuItem(
                                            text = {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    Text(
                                                        model.label,
                                                        fontSize = 13.sp,
                                                        fontWeight = if (model.id == activeModel)
                                                            FontWeight.Bold else FontWeight.Normal
                                                    )
                                                    if (model.supportsImages) {
                                                        Icon(
                                                            Icons.Default.Image, null,
                                                            modifier = Modifier.size(12.dp),
                                                            tint = PortaTertiary
                                                        )
                                                    }
                                                    if (model.quotaRemaining < 1f) {
                                                        Text(
                                                            "${(model.quotaRemaining * 100).toInt()}%",
                                                            fontSize = 10.sp,
                                                            color = if (model.quotaRemaining < 0.2f)
                                                                PortaError else PortaTertiary
                                                        )
                                                    }
                                                }
                                            },
                                            onClick = {
                                                viewModel.selectModel(model.id)
                                                modelDropdownOpen = false
                                            },
                                            leadingIcon = {
                                                if (model.id == activeModel) {
                                                    Icon(
                                                        Icons.Default.Check, null,
                                                        modifier = Modifier.size(16.dp),
                                                        tint = PortaSuccess
                                                    )
                                                }
                                            }
                                        )
                                    }
                                }
                            }

                            // Planner mode toggle
                            val plannerChips = listOf(
                                null to "Auto",
                                "fast" to "Fast",
                                "plan" to "Plan"
                            )
                            plannerChips.forEach { (type, label) ->
                                FilterChip(
                                    selected = plannerType == type,
                                    onClick = { viewModel.setPlannerType(type) },
                                    label = { Text(label, fontSize = 11.sp) },
                                    modifier = Modifier.height(28.dp),
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = PortaPrimary.copy(alpha = 0.15f)
                                    )
                                )
                            }

                            Spacer(Modifier.weight(1f))

                            // Agent running indicator
                            if (agentRunning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = PortaTertiary
                                )
                            }
                        }
                    }

                    // ── Attachment preview strip ──
                    if (attachments.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            attachments.forEachIndexed { idx, att ->
                                Box(
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    if (att.mimeType.startsWith("image/")) {
                                        val bytes = Base64.decode(att.base64, Base64.NO_WRAP)
                                        val bmp = remember(att.base64) {
                                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                        }
                                        if (bmp != null) {
                                            androidx.compose.foundation.Image(
                                                bitmap = bmp.asImageBitmap(),
                                                contentDescription = att.fileName,
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                            )
                                        }
                                    } else {
                                        Icon(
                                            Icons.Default.Description,
                                            att.fileName,
                                            modifier = Modifier.align(Alignment.Center).size(24.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    // Remove button
                                    Icon(
                                        Icons.Default.Close,
                                        "Remove",
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .size(18.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.8f))
                                            .clickable { attachments.removeAt(idx) }
                                            .padding(2.dp),
                                        tint = Color.White
                                    )
                                }
                            }
                        }
                    }
                    // Input row — clean single-row layout
                    Row(
                        modifier = Modifier
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                            .fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Media attach button (+) with popup menu
                        var attachMenuOpen by remember { mutableStateOf(false) }
                        Box {
                            IconButton(
                                onClick = { attachMenuOpen = true },
                                enabled = connectionState == ConnectionState.CONNECTED &&
                                        currentConversationId != null,
                                modifier = Modifier.size(40.dp)
                            ) {
                                BadgedBox(
                                    badge = {
                                        if (attachments.isNotEmpty()) {
                                            Badge { Text("${attachments.size}") }
                                        }
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.AddCircleOutline, "Attach",
                                        tint = if (attachments.isNotEmpty()) PortaTertiary
                                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }

                            DropdownMenu(
                                expanded = attachMenuOpen,
                                onDismissRequest = { attachMenuOpen = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Gallery", fontSize = 14.sp) },
                                    onClick = {
                                        attachMenuOpen = false
                                        imagePickerLauncher.launch("image/*")
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Image, null, modifier = Modifier.size(20.dp))
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Camera", fontSize = 14.sp) },
                                    onClick = {
                                        attachMenuOpen = false
                                        val photoFile = File.createTempFile(
                                            "porta_", ".jpg",
                                            context.cacheDir
                                        )
                                        val uri = FileProvider.getUriForFile(
                                            context,
                                            "${context.packageName}.provider",
                                            photoFile
                                        )
                                        cameraUri = uri
                                        cameraLauncher.launch(uri)
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.CameraAlt, null, modifier = Modifier.size(20.dp))
                                    }
                                )
                            }
                        }

                        // Mic button
                        IconButton(
                            onClick = {
                                if (isListening) viewModel.stopVoiceInput()
                                else viewModel.startVoiceInput()
                            },
                            enabled = connectionState == ConnectionState.CONNECTED &&
                                    currentConversationId != null,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                if (isListening) Icons.Default.MicOff else Icons.Default.Mic,
                                if (isListening) "Stop" else "Voice",
                                tint = if (isListening) PortaError
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        // Text field — takes maximum available width
                        OutlinedTextField(
                            value = displayText,
                            onValueChange = { if (!isListening) inputText = it },
                            modifier = Modifier.weight(1f),
                            placeholder = {
                                Text(
                                    if (attachments.isNotEmpty()) "Describe the image..."
                                    else "Ask Antigravity...",
                                    fontSize = 14.sp
                                )
                            },
                            maxLines = 4,
                            readOnly = isListening,
                            textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(
                                onSend = {
                                    val hasContent = inputText.isNotBlank() || attachments.isNotEmpty()
                                    if (hasContent) {
                                        val media = attachments.map {
                                            mapOf("mimeType" to it.mimeType, "inlineData" to it.base64)
                                        }.ifEmpty { null }
                                        viewModel.sendMessage(
                                            inputText.trim().ifBlank { " " },
                                            media = media
                                        )
                                        inputText = ""
                                        attachments.clear()
                                    }
                                }
                            ),
                            shape = RoundedCornerShape(20.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PortaPrimary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.surfaceVariant,
                                focusedContainerColor = MaterialTheme.colorScheme.surface,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            )
                        )

                        // Send / Stop button
                        if (agentRunning) {
                            FilledIconButton(
                                onClick = { viewModel.stopGeneration() },
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = PortaError
                                ),
                                modifier = Modifier.size(44.dp)
                            ) {
                                Icon(Icons.Default.Stop, "Stop", modifier = Modifier.size(22.dp))
                            }
                        } else {
                            val hasContent = inputText.isNotBlank() || attachments.isNotEmpty()
                            FilledIconButton(
                                onClick = {
                                    if (hasContent) {
                                        val media = attachments.map {
                                            mapOf("mimeType" to it.mimeType, "inlineData" to it.base64)
                                        }.ifEmpty { null }
                                        viewModel.sendMessage(
                                            inputText.trim().ifBlank { " " },
                                            media = media
                                        )
                                        inputText = ""
                                        attachments.clear()
                                    }
                                },
                                enabled = hasContent &&
                                        connectionState == ConnectionState.CONNECTED &&
                                        currentConversationId != null,
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = PortaPrimary
                                ),
                                modifier = Modifier.size(44.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Send, "Send", modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.surface)
        ) {
            // ── Conversation switcher bar ──
            if (connectionState == ConnectionState.CONNECTED && conversations.isNotEmpty()) {
                ConversationSwitcherBar(
                    conversations = conversations,
                    currentConversationId = currentConversationId,
                    onSelectConversation = { id -> viewModel.selectConversation(id) },
                    onNewConversation = { viewModel.createNewConversation() },
                    onOpenFullList = onNavigateToConversations
                )
            }
            // No conversation selected
            if (currentConversationId == null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(
                            Icons.Default.Visibility,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = PortaPrimary.copy(alpha = 0.5f)
                        )
                        Text(
                            "Select or create a conversation",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        FilledTonalButton(onClick = onNavigateToConversations) {
                            Icon(Icons.Default.Forum, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Open Conversations")
                        }
                    }
                }
                return@Column
            }

            // Chat messages with scroll-to-bottom FAB
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    items(chatMessages, key = { "${it.stepIndex}-${it.role}" }) { message ->
                        MessageBubble(
                            message = message,
                            onApproveCommand = { trajectoryId, stepIndex ->
                                viewModel.approveCommandByTrajectory(trajectoryId, stepIndex)
                            },
                            onRejectCommand = { trajectoryId, stepIndex ->
                                viewModel.rejectCommandByTrajectory(trajectoryId, stepIndex)
                            },
                            onApprovePermission = { trajectoryId, stepIndex, allow, scope ->
                                viewModel.handleFilePermissionByTrajectory(
                                    trajectoryId, stepIndex, allow, scope
                                )
                            },
                            onRevert = { stepIndex ->
                                viewModel.revertToStep(stepIndex)
                            }
                        )
                    }

                    // Typing indicator when agent is running
                    if (agentRunning) {
                        item {
                            Row(
                                modifier = Modifier.padding(8.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val infiniteTransition = rememberInfiniteTransition(label = "dots")
                                repeat(3) { i ->
                                    val offsetY by infiniteTransition.animateFloat(
                                        initialValue = 0f,
                                        targetValue = -6f,
                                        animationSpec = infiniteRepeatable(
                                            animation = keyframes {
                                                durationMillis = 800
                                                0f at 0
                                                -6f at 200
                                                0f at 400
                                                0f at 800
                                            },
                                            initialStartOffset = StartOffset(i * 150)
                                        ),
                                        label = "dot$i"
                                    )
                                    Box(
                                        modifier = Modifier
                                            .offset(y = offsetY.dp)
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(PortaPrimary.copy(alpha = 0.6f))
                                    )
                                }
                            }
                        }
                    }
                }

                // Scroll-to-bottom FAB — shown when not at bottom
                val isAtBottom by remember {
                    derivedStateOf {
                        val lastIndex = chatMessages.size - 1
                        if (lastIndex < 0) true
                        else listState.layoutInfo.visibleItemsInfo.any { it.index >= lastIndex }
                    }
                }

                if (!isAtBottom && chatMessages.isNotEmpty()) {
                    val scope = rememberCoroutineScope()
                    SmallFloatingActionButton(
                        onClick = {
                            scope.launch {
                                listState.animateScrollToItem(chatMessages.size - 1)
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp),
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = PortaPrimary
                    ) {
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            "Scroll to bottom",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}
