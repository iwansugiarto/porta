package id.infinia.porta.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.JsonObject
import id.infinia.porta.ui.theme.*
import id.infinia.porta.viewmodel.BridgeViewModel

/**
 * Conversation list screen — workspace-grouped like the PWA sidebar.
 *
 * Features:
 * - Conversations grouped by workspace name
 * - Running indicator (spinner) per conversation
 * - Swipe/long-press to delete
 * - New conversation button
 * - Active conversation highlight
 */

data class WorkspaceGroup(
    val name: String,
    val conversations: List<Pair<String, JsonObject>>,
    val hasRunning: Boolean
)

private fun extractWorkspaceName(summary: JsonObject): String {
    val workspaces = summary.getAsJsonArray("workspaces")
    if (workspaces == null || workspaces.size() == 0) return "Others"
    val ws = workspaces[0].asJsonObject
    val repo = ws.getAsJsonObject("repository")?.get("computedName")?.asString
    if (repo != null) return repo.substringAfterLast("/")
    val uri = ws.get("workspaceFolderAbsoluteUri")?.asString
    if (uri != null) return uri.substringAfterLast("/")
    return "Others"
}

private fun relativeTime(iso: String?): String {
    if (iso == null) return ""
    return try {
        val diff = System.currentTimeMillis() -
                java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                    .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                    .parse(iso.take(19))!!.time
        val mins = diff / 60_000
        when {
            mins < 1 -> "just now"
            mins < 60 -> "${mins}m ago"
            mins < 1440 -> "${mins / 60}h ago"
            else -> "${mins / 1440}d ago"
        }
    } catch (_: Exception) { "" }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(
    viewModel: BridgeViewModel,
    onBack: () -> Unit,
    onSelectConversation: (String) -> Unit
) {
    val conversations by viewModel.conversations.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val currentId by viewModel.currentConversationId.collectAsState()

    // Group conversations by workspace
    val groups = remember(conversations) {
        val map = mutableMapOf<String, MutableList<Pair<String, JsonObject>>>()
        for ((id, summary) in conversations) {
            val name = extractWorkspaceName(summary)
            map.getOrPut(name) { mutableListOf() }.add(id to summary)
        }
        map.entries
            .filter { it.key != "Others" }
            .map { (name, convos) ->
                convos.sortByDescending { it.second.get("lastModifiedTime")?.asString ?: "" }
                WorkspaceGroup(
                    name = name,
                    conversations = convos,
                    hasRunning = convos.any {
                        it.second.get("status")?.asString == "CASCADE_RUN_STATUS_RUNNING"
                    }
                )
            }
            .sortedWith(compareByDescending<WorkspaceGroup> { it.hasRunning }
                .thenByDescending {
                    it.conversations.maxOfOrNull { c ->
                        c.second.get("lastModifiedTime")?.asString ?: ""
                    } ?: ""
                }
            )
    }

    // Delete confirmation dialog
    var deleteTarget by remember { mutableStateOf<Pair<String, String>?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Conversations") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.loadConversations() }) {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }
                    IconButton(onClick = { viewModel.createNewConversation() }) {
                        Icon(Icons.Default.Add, "New")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PortaSurface)
            )
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(PortaSurface)
        ) {
            if (isLoading && conversations.isEmpty()) {
                CircularProgressIndicator(
                    Modifier.align(Alignment.Center),
                    color = PortaPrimary
                )
            } else if (groups.isEmpty()) {
                Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Default.Forum, null,
                        modifier = Modifier.size(48.dp),
                        tint = PortaPrimary.copy(alpha = 0.3f)
                    )
                    Text(
                        "No conversations yet",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    FilledTonalButton(onClick = { viewModel.createNewConversation() }) {
                        Icon(Icons.Default.Add, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("New Conversation")
                    }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    groups.forEach { group ->
                        // Workspace header
                        item(key = "header-${group.name}") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    Icons.Default.Folder, null,
                                    modifier = Modifier.size(14.dp),
                                    tint = PortaTertiary
                                )
                                Text(
                                    group.name,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = PortaTertiary,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    "${group.conversations.size}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                )
                                if (group.hasRunning) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(12.dp),
                                        strokeWidth = 1.5.dp,
                                        color = PortaTertiary
                                    )
                                }
                            }
                        }

                        // Conversation items
                        items(
                            group.conversations,
                            key = { it.first }
                        ) { (id, summary) ->
                            val title = summary.get("summary")?.asString ?: id.take(8) + "…"
                            val stepCount = summary.get("stepCount")?.asInt ?: 0
                            val lastModified = summary.get("lastModifiedTime")?.asString
                            val isRunning = summary.get("status")?.asString == "CASCADE_RUN_STATUS_RUNNING"
                            val isActive = id == currentId

                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = when {
                                        isActive -> PortaPrimary.copy(alpha = 0.12f)
                                        else -> PortaSurfaceVariant
                                    }
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .clickable { onSelectConversation(id) }
                                        .padding(12.dp)
                                        .fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Status indicator
                                    if (isRunning) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            color = PortaTertiary
                                        )
                                    } else {
                                        Icon(
                                            Icons.Default.ChatBubbleOutline, null,
                                            modifier = Modifier.size(16.dp),
                                            tint = if (isActive) PortaPrimary
                                            else PortaPrimary.copy(alpha = 0.3f)
                                        )
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            title,
                                            fontWeight = FontWeight.Medium,
                                            fontSize = 13.sp,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                relativeTime(lastModified),
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                            )
                                            Text(
                                                "· $stepCount steps",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                            )
                                        }
                                    }
                                    // Delete button
                                    IconButton(
                                        onClick = { deleteTarget = id to title },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Delete, "Delete",
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Delete confirmation dialog
    deleteTarget?.let { (id, title) ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete conversation?") },
            text = {
                Text(
                    "\"$title\" will be permanently deleted.",
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteConversation(id)
                        deleteTarget = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = PortaError)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            }
        )
    }
}
