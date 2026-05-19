package id.infinia.porta.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
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

/**
 * Home screen — workspace tiles + recent conversations carousel.
 *
 * Navigation:
 * - Tap workspace tile → ConversationsScreen (filtered)
 * - Tap recent card → ChatScreen (direct)
 * - FAB → new conversation
 * - Settings icon → SettingsScreen
 */

// Auto-assigned workspace colors based on name hash
private val workspaceColors = listOf(
    Color(0xFF6366F1), // Indigo
    Color(0xFF8B5CF6), // Violet
    Color(0xFF06B6D4), // Cyan
    Color(0xFF10B981), // Emerald
    Color(0xFFF59E0B), // Amber
    Color(0xFFEC4899), // Pink
    Color(0xFF3B82F6), // Blue
    Color(0xFFF97316), // Orange
)

private fun workspaceColor(name: String): Color {
    val hash = name.hashCode().and(0x7FFFFFFF)
    return workspaceColors[hash % workspaceColors.size]
}



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: BridgeViewModel,
    onNavigateToSettings: () -> Unit,
    onNavigateToWorkspace: (workspaceName: String) -> Unit,
    onSelectConversation: (String) -> Unit,
    onNewConversation: () -> Unit
) {
    val workspaces by viewModel.workspaceGroups.collectAsState()
    val recentConvos by viewModel.recentConversations.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val conversations by viewModel.conversations.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()

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
                onClick = onNewConversation,
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
            } else if (conversations.isEmpty()) {
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
                            Icons.Default.Workspaces, null,
                            modifier = Modifier.size(64.dp),
                            tint = PortaPrimary.copy(alpha = 0.3f)
                        )
                        Text(
                            "No workspaces yet",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                        Text(
                            "Start a conversation from your desktop to see it here",
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
                        val totalConvos = conversations.size
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
                                "$totalConvos conversations across ${workspaces.size} workspaces",
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
                                    Icons.Default.Schedule, null,
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

                    // ── Workspaces section ──
                    item(key = "ws-header") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.Folder, null,
                                modifier = Modifier.size(18.dp),
                                tint = PortaPrimary
                            )
                            Text(
                                "Workspaces",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                "${workspaces.size}",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                            )
                        }
                    }

                    items(workspaces, key = { it.name }) { ws ->
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
                            WorkspaceTile(
                                workspace = ws,
                                onClick = { onNavigateToWorkspace(ws.name) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentConvoCard(
    id: String,
    summary: JsonObject,
    onClick: () -> Unit
) {
    val title = summary.get("summary")?.asString ?: id.take(8) + "…"
    val lastModified = summary.get("lastModifiedTime")?.asString
    val isRunning = summary.get("status")?.asString == "CASCADE_RUN_STATUS_RUNNING"
    val stepCount = summary.get("stepCount")?.asInt ?: 0

    // Extract workspace name for color
    val wsName = run {
        val workspaces = summary.getAsJsonArray("workspaces")
        if (workspaces != null && workspaces.size() > 0) {
            val ws = workspaces[0].asJsonObject
            ws.getAsJsonObject("repository")?.get("computedName")?.asString?.substringAfterLast("/")
                ?: ws.get("workspaceFolderAbsoluteUri")?.asString?.substringAfterLast("/")
                ?: "Others"
        } else "Others"
    }
    val color = workspaceColor(wsName)

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
            // Top: workspace badge + status
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
                Text(
                    wsName,
                    fontSize = 10.sp,
                    color = color,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(6.dp))

            // Title
            Text(
                title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
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
                Text(
                    "· $stepCount steps",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }
        }
    }
}

@Composable
private fun WorkspaceTile(
    workspace: BridgeViewModel.WorkspaceInfo,
    onClick: () -> Unit
) {
    val color = workspaceColor(workspace.name)
    val initial = workspace.name.first().uppercaseChar()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Letter avatar
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(color, color.copy(alpha = 0.7f))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    initial.toString(),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            // Name + stats
            Column(Modifier.weight(1f)) {
                Text(
                    workspace.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${workspace.totalCount} conversations",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    if (workspace.activeCount > 0) {
                        Text(
                            "· ${workspace.activeCount} active",
                            fontSize = 12.sp,
                            color = PortaSuccess,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Active indicator
            if (workspace.activeCount > 0) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = PortaTertiary
                )
            } else {
                Icon(
                    Icons.Default.ChevronRight, null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                )
            }
        }
    }
}
