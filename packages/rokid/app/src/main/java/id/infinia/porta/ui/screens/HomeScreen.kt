package id.infinia.porta.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.animation.core.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.JsonObject
import id.infinia.porta.ui.UiUtils
import id.infinia.porta.ui.theme.*
import id.infinia.porta.viewmodel.BridgeViewModel
import kotlinx.coroutines.launch

/**
 * Home screen — conversation timeline with recent carousel.
 *
 * Navigation:
 * - Tap conversation → ChatScreen (direct)
 * - FAB → new conversation
 * - Settings icon → SettingsScreen
 */

// Auto-assigned conversation colors based on title hash
private val conversationColors = listOf(
    Color(0xFF6366F1), // Indigo
    Color(0xFF8B5CF6), // Violet
    Color(0xFF06B6D4), // Cyan
    Color(0xFF10B981), // Emerald
    Color(0xFFF59E0B), // Amber
    Color(0xFFEC4899), // Pink
    Color(0xFF3B82F6), // Blue
    Color(0xFFF97316), // Orange
)

private fun titleColor(title: String): Color {
    val hash = title.hashCode().and(0x7FFFFFFF)
    return conversationColors[hash % conversationColors.size]
}

// Section header icons
private val sectionIcons = mapOf(
    "Today" to Icons.Default.Today,
    "Yesterday" to Icons.Default.History,
    "This Week" to Icons.Default.DateRange,
    "Earlier" to Icons.Default.Schedule,
)


@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    viewModel: BridgeViewModel,
    onOpenDrawer: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToWorkspace: (workspaceName: String) -> Unit,
    onSelectConversation: (String) -> Unit,
    onNewConversation: () -> Unit
) {
    val timeGroups by viewModel.timeGroupedConversations.collectAsState()
    val recentConvos by viewModel.recentConversations.collectAsState()
    val visibleCount by viewModel.visibleConversationCount.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val conversations by viewModel.conversations.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val workspaces by viewModel.workspaces.collectAsState()

    // Workspace picker state
    var showWorkspacePicker by remember { mutableStateOf(false) }


    // Auto-load on first render
    LaunchedEffect(Unit) {
        if (conversations.isEmpty()) {
            viewModel.connect()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "Porta",
                            fontWeight = FontWeight.Bold,
                            fontSize = 22.sp
                        )
                        // Animated connection indicator
                        val indicatorColor = when (connectionState) {
                            id.infinia.porta.shared.protocol.ConnectionState.CONNECTED -> PortaSuccess
                            id.infinia.porta.shared.protocol.ConnectionState.CONNECTING,
                            id.infinia.porta.shared.protocol.ConnectionState.RECONNECTING -> PortaWarning
                            else -> PortaError
                        }
                        val isConnecting = connectionState == id.infinia.porta.shared.protocol.ConnectionState.CONNECTING ||
                            connectionState == id.infinia.porta.shared.protocol.ConnectionState.RECONNECTING
                        val pulseAlpha = if (isConnecting) {
                            val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                            val alpha by infiniteTransition.animateFloat(
                                initialValue = 0.3f,
                                targetValue = 1f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(800, easing = FastOutSlowInEasing),
                                    repeatMode = RepeatMode.Reverse
                                ),
                                label = "pulseAlpha"
                            )
                            alpha
                        } else 1f
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(indicatorColor.copy(alpha = pulseAlpha))
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = "Menu")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.loadConversations() }) {
                        Icon(Icons.Default.Refresh, "Refresh")
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
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    if (workspaces.isNotEmpty()) {
                        showWorkspacePicker = true
                    } else {
                        viewModel.createNewConversation()
                        onNewConversation()
                    }
                },
                containerColor = PortaPrimary,
                contentColor = Color.White
            ) {
                Icon(Icons.Default.Add, "New Conversation")
            }
        }
    ) { padding ->
        val isRefreshing = isLoading && conversations.isNotEmpty()

        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.loadConversations() },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (isLoading && conversations.isEmpty()) {
                // Initial loading state
                Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator(color = PortaPrimary)
                        Text(
                            "Connecting to Porta...",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }
            } else if (visibleCount == 0 && !isLoading) {
                // Empty state
                Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Default.ChatBubbleOutline, null,
                            modifier = Modifier.size(64.dp),
                            tint = PortaPrimary.copy(alpha = 0.3f)
                        )
                        Text(
                            "No conversations yet",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                        Text(
                            "Start a conversation from Antigravity to see it here",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        )
                    }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 80.dp) // room for FAB
                ) {
                    // ── Greeting header ──
                    item(key = "greeting") {
                        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                        val greeting = when {
                            hour < 12 -> "Good Morning"
                            hour < 17 -> "Good Afternoon"
                            else -> "Good Evening"
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Text(
                                greeting,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                "$visibleCount conversations",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                    }

                    // ── Recent conversations carousel ──
                    if (recentConvos.isNotEmpty()) {
                        item(key = "recent-header") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Default.Bolt, null,
                                    modifier = Modifier.size(18.dp),
                                    tint = PortaTertiary
                                )
                                Text(
                                    "Recent",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                        item(key = "recent-carousel") {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                items(
                                    recentConvos,
                                    key = { it.first }
                                ) { (id, summary) ->
                                    RecentConvoCard(
                                        id = id,
                                        summary = summary,
                                        onClick = { onSelectConversation(id) }
                                    )
                                }
                            }
                        }
                    }

                    // ── Time-grouped conversation timeline ──
                    for (group in timeGroups) {
                        // Section header
                        item(key = "section-${group.label}") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    sectionIcons[group.label] ?: Icons.Default.Schedule, null,
                                    modifier = Modifier.size(18.dp),
                                    tint = PortaPrimary
                                )
                                Text(
                                    group.label,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    "${group.conversations.size}",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                )
                            }
                        }

                        // Conversation rows
                        items(
                            group.conversations,
                            key = { it.first }
                        ) { (id, summary) ->
                            Box(
                                modifier = Modifier.animateItem(
                                    fadeInSpec = tween(300),
                                    fadeOutSpec = tween(200),
                                    placementSpec = spring(
                                        stiffness = Spring.StiffnessMediumLow,
                                        dampingRatio = Spring.DampingRatioLowBouncy
                                    )
                                )
                            ) {
                                ConversationRow(
                                    id = id,
                                    summary = summary,
                                    onClick = { onSelectConversation(id) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Workspace picker bottom sheet
    if (showWorkspacePicker) {
        WorkspacePickerSheet(
            workspaces = workspaces,
            onSelect = { wsUri ->
                showWorkspacePicker = false
                viewModel.createNewConversation(wsUri)
                onNewConversation()
            },
            onDismiss = { showWorkspacePicker = false }
        )
    }
}

// ── Workspace Picker Bottom Sheet ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkspacePickerSheet(
    workspaces: List<BridgeViewModel.WorkspaceOption>,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            // Header
            Text(
                "New Conversation",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                "Select a workspace for context",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // No workspace option
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .clickable { onSelect(null) }
                        .padding(14.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(PortaTertiary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.ChatBubbleOutline, null,
                            modifier = Modifier.size(20.dp),
                            tint = PortaTertiary
                        )
                    }
                    Column {
                        Text(
                            "No workspace",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            "General conversation without project context",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            // Workspace list
            workspaces.forEach { ws ->
                val wsColor = titleColor(ws.name)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .clickable { onSelect(ws.uri) }
                            .padding(14.dp)
                            .fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Folder icon with color
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(wsColor, wsColor.copy(alpha = 0.7f))
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Folder, null,
                                modifier = Modifier.size(20.dp),
                                tint = Color.White
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                ws.name,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                ws.uri.removePrefix("file://"),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Icon(
                            Icons.Default.ChevronRight, null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        )
                    }
                }
            }
        }
    }
}

// ── Recent Conversation Carousel Card ──

@Composable
private fun RecentConvoCard(
    id: String,
    summary: JsonObject,
    onClick: () -> Unit
) {
    val title = UiUtils.displayTitle(summary)
    val lastModified = summary.get("lastModifiedTime")?.asString
    val isRunning = summary.get("status")?.asString == "CASCADE_RUN_STATUS_RUNNING"
    val stepCount = summary.get("stepCount")?.asInt ?: 0
    val color = titleColor(title)

    Card(
        modifier = Modifier
            .width(200.dp)
            .animateContentSize(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(14.dp)
        ) {
            // Top: color accent + status
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isRunning) PortaSuccess else color.copy(alpha = 0.5f))
                )
                if (isRunning) {
                    Text(
                        "Running",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = PortaSuccess
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            // Title
            Text(
                title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(Modifier.height(4.dp))

            // Bottom: time + steps
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    UiUtils.relativeTime(lastModified),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
                if (stepCount > 0) {
                    Text(
                        "· $stepCount steps",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            }
        }
    }
}

// ── Conversation Row (timeline item) ──

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    id: String,
    summary: JsonObject,
    onClick: () -> Unit
) {
    val title = UiUtils.displayTitle(summary)
    val isRunning = summary.get("status")?.asString == "CASCADE_RUN_STATUS_RUNNING"
    val stepCount = summary.get("stepCount")?.asInt ?: 0
    val lastModified = summary.get("lastModifiedTime")?.asString
    val color = titleColor(title)
    val initial = title.first().uppercaseChar()

    // Extract workspace name if available (for subtle badge)
    val wsName = UiUtils.extractWorkspaceName(summary).let {
        if (it == "Others") null else it
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(14.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Letter avatar
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(color, color.copy(alpha = 0.7f))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                } else {
                    Text(
                        initial.toString(),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }

            // Title + metadata
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 18.sp
                )

                Spacer(Modifier.height(2.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Running badge
                    if (isRunning) {
                        Text(
                            "● Running",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = PortaSuccess
                        )
                        Text("·", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
                    }

                    // Step count
                    if (stepCount > 0) {
                        Text(
                            "$stepCount steps",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                        Text("·", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
                    }

                    // Time
                    Text(
                        UiUtils.relativeTime(lastModified),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )

                    // Workspace badge (if available)
                    if (wsName != null) {
                        Text("·", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
                        Text(
                            wsName,
                            fontSize = 11.sp,
                            color = PortaPrimary.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 100.dp)
                        )
                    }
                }
            }

            // Chevron
            Icon(
                Icons.Default.ChevronRight, null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
            )
        }
    }
}
