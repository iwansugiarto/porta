package id.infinia.porta.ui.screens

import androidx.compose.animation.*
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.JsonObject
import id.infinia.porta.ui.UiUtils
import id.infinia.porta.data.Project
import id.infinia.porta.data.ProjectStatus
import id.infinia.porta.ui.components.ConversationListSkeleton
import id.infinia.porta.ui.components.EditProjectDialog
import id.infinia.porta.ui.theme.*
import id.infinia.porta.viewmodel.BridgeViewModel
import kotlinx.coroutines.launch

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



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(
    viewModel: BridgeViewModel,
    onOpenDrawer: () -> Unit,
    onBack: () -> Unit,
    onSelectConversation: (String) -> Unit,
    onSelectProject: (Project) -> Unit,
    workspaceFilter: String? = null
) {
    val conversations by viewModel.conversations.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val currentId by viewModel.currentConversationId.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val projects by viewModel.projects.collectAsState()
    var editProjectTarget by remember { mutableStateOf<Project?>(null) }

    // Group conversations by workspace
    val groups = remember(conversations, workspaceFilter) {
        val map = mutableMapOf<String, MutableList<Pair<String, JsonObject>>>()
        for ((id, summary) in conversations) {
            val name = UiUtils.extractWorkspaceName(summary)
            // If workspace filter is set, only include matching workspace
            if (workspaceFilter != null && name != workspaceFilter) continue
            map.getOrPut(name) { mutableListOf() }.add(id to summary)
        }
        map.entries
            .filter { workspaceFilter != null || it.key != "Others" }
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

    // Search state
    var searchQuery by remember { mutableStateOf("") }
    var searchVisible by remember { mutableStateOf(false) }

    // Filtered groups based on search
    val filteredGroups = remember(groups, searchQuery) {
        if (searchQuery.isBlank()) groups
        else groups.mapNotNull { group ->
            val filtered = group.conversations.filter { (_, summary) ->
                val title = summary.get("summary")?.asString ?: ""
                title.contains(searchQuery, ignoreCase = true) ||
                    group.name.contains(searchQuery, ignoreCase = true)
            }
            if (filtered.isEmpty()) null
            else group.copy(conversations = filtered)
        }
    }

    val matchedProjects = remember(projects, searchQuery) {
        if (searchQuery.isBlank()) emptyList()
        else projects.filter { it.title.contains(searchQuery, ignoreCase = true) }
    }
    val matchedConversations = remember(conversations, searchQuery) {
        if (searchQuery.isBlank()) emptyList()
        else conversations.entries
            .filter { !UiUtils.isGhostConversation(it.value) }
            .filter { (_, summary) ->
                val title = summary.get("summary")?.asString ?: ""
                title.contains(searchQuery, ignoreCase = true)
            }
            .map { it.key to it.value }
    }
    val hasSearchMatches = remember(matchedProjects, matchedConversations) {
        matchedProjects.isNotEmpty() || matchedConversations.isNotEmpty()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(workspaceFilter ?: "Conversations") },
                navigationIcon = {
                    if (workspaceFilter == null) {
                        IconButton(onClick = onOpenDrawer) {
                            Icon(Icons.Default.Menu, "Menu")
                        }
                    } else {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = {
                        searchVisible = !searchVisible
                        if (!searchVisible) searchQuery = ""
                    }) {
                        Icon(
                            if (searchVisible) Icons.Default.SearchOff else Icons.Default.Search,
                            "Search"
                        )
                    }
                    IconButton(onClick = { viewModel.loadConversations() }) {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }
                    IconButton(onClick = { viewModel.createNewConversation() }) {
                        Icon(Icons.Default.Add, "New")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.surface)
        ) {
            // Search bar
            AnimatedVisibility(
                visible = searchVisible,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search conversations and projects…", fontSize = 14.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(18.dp)) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, "Clear", Modifier.size(18.dp))
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PortaPrimary,
                        cursorColor = PortaPrimary
                    )
                )
            }

            Box(Modifier.fillMaxSize()) {
                if (isLoading && conversations.isEmpty()) {
                    ConversationListSkeleton(Modifier.fillMaxSize())
                } else if (searchQuery.isNotBlank() && !hasSearchMatches) {
                    Column(
                        Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.SearchOff, null,
                            modifier = Modifier.size(48.dp),
                            tint = PortaPrimary.copy(alpha = 0.3f)
                        )
                        Text(
                            "No matches for \"$searchQuery\"",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
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
                    val isRefreshing = isLoading && conversations.isNotEmpty()
                    PullToRefreshBox(
                        isRefreshing = isRefreshing,
                        onRefresh = { viewModel.loadConversations() },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        LazyColumn(
                            Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (searchQuery.isNotBlank()) {
                                // --- PROJECTS MATCHED ---
                                if (matchedProjects.isNotEmpty()) {
                                    item(key = "search-projects-header") {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 8.dp, bottom = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                Icons.Default.FolderOpen, null,
                                                modifier = Modifier.size(14.dp),
                                                tint = PortaPrimary
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                "Projects Matched",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = PortaPrimary,
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                    }
                                    items(matchedProjects, key = { "proj-${it.id}" }) { proj ->
                                        Card(
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(10.dp),
                                            colors = CardDefaults.cardColors(
                                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                                            )
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .clickable { onSelectProject(proj) }
                                                    .padding(12.dp)
                                                    .fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                val statusColor = when (proj.status) {
                                                    ProjectStatus.BLOCKED -> PortaError
                                                    ProjectStatus.IN_PROGRESS -> PortaWarning
                                                    ProjectStatus.IDLE -> PortaSuccess
                                                }
                                                Box(
                                                    modifier = Modifier
                                                        .size(8.dp)
                                                        .clip(CircleShape)
                                                        .background(statusColor)
                                                )
                                                Spacer(Modifier.width(10.dp))
                                                Column(Modifier.weight(1f)) {
                                                    Text(
                                                        proj.title,
                                                        fontWeight = FontWeight.Medium,
                                                        fontSize = 13.sp,
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis,
                                                        color = MaterialTheme.colorScheme.onSurface
                                                    )
                                                    Row(
                                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Text(
                                                            proj.status.value,
                                                            fontSize = 11.sp,
                                                            color = statusColor,
                                                            fontWeight = FontWeight.SemiBold
                                                        )
                                                        if (proj.timeBadge != null) {
                                                            Text(
                                                                "· ${proj.timeBadge}",
                                                                fontSize = 11.sp,
                                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                                            )
                                                        }
                                                    }
                                                }
                                                IconButton(onClick = { editProjectTarget = proj }) {
                                                    Icon(
                                                        Icons.Default.Settings,
                                                        "Project Settings",
                                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                // --- CONVERSATIONS MATCHED ---
                                if (matchedConversations.isNotEmpty()) {
                                    item(key = "search-convos-header") {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 16.dp, bottom = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                Icons.Default.Forum, null,
                                                modifier = Modifier.size(14.dp),
                                                tint = PortaTertiary
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                "Conversations Matched",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = PortaTertiary,
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                    }
                                    items(matchedConversations, key = { "convo-${it.first}" }) { (id, summary) ->
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
                                                    else -> MaterialTheme.colorScheme.surfaceVariant
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
                                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Text(
                                                            UiUtils.relativeTime(lastModified),
                                                            fontSize = 11.sp,
                                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                                        )
                                                        Text(
                                                            "· $stepCount steps",
                                                            fontSize = 11.sp,
                                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                                        )
                                                        val status = summary.get("status")?.asString
                                                        when (status) {
                                                            "CASCADE_RUN_STATUS_FINISHED" -> Text(
                                                                "✓ Done", fontSize = 10.sp,
                                                                color = PortaSuccess,
                                                                fontWeight = FontWeight.Medium
                                                            )
                                                            "CASCADE_RUN_STATUS_ERROR" -> Text(
                                                                "✗ Error", fontSize = 10.sp,
                                                                color = PortaError,
                                                                fontWeight = FontWeight.Medium
                                                            )
                                                            "CASCADE_RUN_STATUS_WAITING" -> Text(
                                                                "⏸ Waiting", fontSize = 10.sp,
                                                                color = PortaWarning,
                                                                fontWeight = FontWeight.Medium
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            } else {
                                filteredGroups.forEach { group ->
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

                                        val dismissState = rememberSwipeToDismissBoxState(
                                            confirmValueChange = { value ->
                                                if (value == SwipeToDismissBoxValue.EndToStart) {
                                                    scope.launch {
                                                        val result = snackbarHostState.showSnackbar(
                                                            message = "\"${title.take(30)}\" deleted",
                                                            actionLabel = "Undo",
                                                            duration = SnackbarDuration.Short
                                                        )
                                                        if (result != SnackbarResult.ActionPerformed) {
                                                            viewModel.deleteConversation(id)
                                                        }
                                                    }
                                                }
                                                false
                                            }
                                        )

                                        SwipeToDismissBox(
                                            state = dismissState,
                                            backgroundContent = {
                                                Box(
                                                    Modifier
                                                        .fillMaxSize()
                                                        .background(
                                                            MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
                                                            RoundedCornerShape(10.dp)
                                                        )
                                                        .padding(end = 20.dp),
                                                    contentAlignment = Alignment.CenterEnd
                                                ) {
                                                    Icon(
                                                        Icons.Default.Delete,
                                                        "Delete",
                                                        tint = MaterialTheme.colorScheme.error
                                                    )
                                                }
                                            },
                                            enableDismissFromStartToEnd = false,
                                            enableDismissFromEndToStart = true
                                        ) {
                                            Card(
                                                modifier = Modifier.fillMaxWidth(),
                                                shape = RoundedCornerShape(10.dp),
                                                colors = CardDefaults.cardColors(
                                                    containerColor = when {
                                                        isActive -> PortaPrimary.copy(alpha = 0.12f)
                                                        else -> MaterialTheme.colorScheme.surfaceVariant
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
                                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Text(
                                                                UiUtils.relativeTime(lastModified),
                                                                fontSize = 11.sp,
                                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                                            )
                                                            Text(
                                                                "· $stepCount steps",
                                                                fontSize = 11.sp,
                                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                                            )
                                                            val status = summary.get("status")?.asString
                                                            when (status) {
                                                                "CASCADE_RUN_STATUS_FINISHED" -> Text(
                                                                    "✓ Done", fontSize = 10.sp,
                                                                    color = PortaSuccess,
                                                                    fontWeight = FontWeight.Medium
                                                                )
                                                                "CASCADE_RUN_STATUS_ERROR" -> Text(
                                                                    "✗ Error", fontSize = 10.sp,
                                                                    color = PortaError,
                                                                    fontWeight = FontWeight.Medium
                                                                )
                                                                "CASCADE_RUN_STATUS_WAITING" -> Text(
                                                                    "⏸ Waiting", fontSize = 10.sp,
                                                                    color = PortaWarning,
                                                                    fontWeight = FontWeight.Medium
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
                        }
                    } // PullToRefreshBox
                }
            } // Box
        } // Column
    }

    editProjectTarget?.let { proj ->
        EditProjectDialog(
            project = proj,
            onDismiss = { editProjectTarget = null },
            onSave = { title, status ->
                viewModel.updateProjectTitle(proj.id, title)
                viewModel.updateProjectStatus(proj.id, status)
                editProjectTarget = null
            },
            onDelete = {
                viewModel.deleteProject(proj.id)
                editProjectTarget = null
            }
        )
    }
}
