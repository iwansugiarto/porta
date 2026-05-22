package id.infinia.porta.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.gson.JsonObject
import id.infinia.porta.data.Project
import id.infinia.porta.data.ProjectStatus
import id.infinia.porta.ui.UiUtils
import id.infinia.porta.ui.theme.*
import id.infinia.porta.viewmodel.BridgeViewModel

private const val PREVIEW_COUNT = 6

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsDrawerContent(
    viewModel: BridgeViewModel,
    activeScreen: String,
    onSelectProject: (Project) -> Unit,
    onSelectConversation: (String) -> Unit,
    onNewConversation: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val projects by viewModel.projects.collectAsState()
    val activeProjectId by viewModel.activeProjectId.collectAsState()
    val recentConversations by viewModel.recentConversations.collectAsState()
    val pinnedConversationIds by viewModel.pinnedConversationIds.collectAsState()
    var contextConversationTarget by remember { mutableStateOf<Pair<String, JsonObject>?>(null) }

    val pinnedConversations = remember(recentConversations, pinnedConversationIds) {
        recentConversations.filter { it.first in pinnedConversationIds }
    }
    val nonPinnedConversations = remember(recentConversations, pinnedConversationIds) {
        recentConversations.filter { it.first !in pinnedConversationIds }
    }

    // Dialog state
    var showCreateDialog by remember { mutableStateOf(false) }
    var editProjectTarget by remember { mutableStateOf<Project?>(null) }
    var showExplorerDialog by remember { mutableStateOf(false) }
    var explorerInitialFilter by remember { mutableStateOf<ProjectStatus?>(null) }

    // Collapsible states
    var blockedCollapsed by remember { mutableStateOf(false) }
    var inProgressCollapsed by remember { mutableStateOf(false) }
    var idleCollapsed by remember { mutableStateOf(false) }

    val blockedList = remember(projects) { projects.filter { it.status == ProjectStatus.BLOCKED } }
    val inProgressList = remember(projects) { projects.filter { it.status == ProjectStatus.IN_PROGRESS } }
    val idleList = remember(projects) { projects.filter { it.status == ProjectStatus.IDLE } }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // --- Brand Header ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Porta",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = PortaPrimary
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        }

        // --- Fast Action Quick buttons ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onNewConversation,
                colors = ButtonDefaults.buttonColors(containerColor = PortaPrimary),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("New Chat", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // --- Projects Scrolling list ---
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            if (pinnedConversations.isNotEmpty()) {
                item {
                    Text(
                        text = "Pinned Chats",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                items(pinnedConversations, key = { "pinned_${it.first}" }) { (id, summary) ->
                    ConversationDrawerRow(
                        id = id,
                        summary = summary,
                        onClick = { onSelectConversation(id) },
                        onLongClick = { contextConversationTarget = id to summary }
                    )
                }
                item {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                    )
                }
            }

            // Projects Header Row
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Projects",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    // Filter / Search Icon (opens explorer)
                    IconButton(
                        onClick = {
                            explorerInitialFilter = null
                            showExplorerDialog = true
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Default.Tune,
                            contentDescription = "Explorer",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    // Create Project folder plus button
                    IconButton(
                        onClick = { showCreateDialog = true },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Default.CreateNewFolder,
                            contentDescription = "Create Project",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }

            // 1. Blocked Category
            item {
                CategoryHeaderRow(
                    title = "Blocked",
                    count = blockedList.size,
                    icon = Icons.Default.Error,
                    iconColor = PortaError,
                    collapsed = blockedCollapsed,
                    onClick = { blockedCollapsed = !blockedCollapsed }
                )
            }
            if (!blockedCollapsed) {
                items(blockedList, key = { it.id }) { proj ->
                    val repoName = remember(proj.title, recentConversations) {
                        getProjectRepoName(proj.title, recentConversations)
                    }
                    ProjectRow(
                        project = proj,
                        isActive = proj.id == activeProjectId,
                        repoName = repoName,
                        onClick = { onSelectProject(proj) },
                        onEditClick = { editProjectTarget = proj }
                    )
                }
            }

            // 2. In Progress Category
            item {
                CategoryHeaderRow(
                    title = "In Progress",
                    count = inProgressList.size,
                    icon = Icons.Default.Pending,
                    iconColor = PortaWarning,
                    collapsed = inProgressCollapsed,
                    onClick = { inProgressCollapsed = !inProgressCollapsed }
                )
            }
            if (!inProgressCollapsed) {
                items(inProgressList, key = { it.id }) { proj ->
                    val repoName = remember(proj.title, recentConversations) {
                        getProjectRepoName(proj.title, recentConversations)
                    }
                    ProjectRow(
                        project = proj,
                        isActive = proj.id == activeProjectId,
                        repoName = repoName,
                        onClick = { onSelectProject(proj) },
                        onEditClick = { editProjectTarget = proj }
                    )
                }
            }

            // 3. Idle Category
            item {
                CategoryHeaderRow(
                    title = "Idle",
                    count = idleList.size,
                    icon = Icons.Default.CheckCircle,
                    iconColor = PortaSuccess,
                    collapsed = idleCollapsed,
                    onClick = { idleCollapsed = !idleCollapsed }
                )
            }
            if (!idleCollapsed) {
                items(idleList.take(PREVIEW_COUNT), key = { it.id }) { proj ->
                    val repoName = remember(proj.title, recentConversations) {
                        getProjectRepoName(proj.title, recentConversations)
                    }
                    ProjectRow(
                        project = proj,
                        isActive = proj.id == activeProjectId,
                        repoName = repoName,
                        onClick = { onSelectProject(proj) },
                        onEditClick = { editProjectTarget = proj }
                    )
                }
                if (idleList.size > PREVIEW_COUNT) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Text(
                                text = "See all (${idleList.size})",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = PortaPrimary,
                                modifier = Modifier
                                    .clickable {
                                        explorerInitialFilter = ProjectStatus.IDLE
                                        showExplorerDialog = true
                                    }
                                    .padding(vertical = 4.dp, horizontal = 8.dp)
                            )
                        }
                    }
                }
            }

            // Divider
            item {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                )
            }

            // --- Conversations Header ---
            item {
                Text(
                    text = "Conversations",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            // Flat Conversation Items
            if (nonPinnedConversations.isEmpty()) {
                item {
                    Text(
                        text = "No recent chats",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            } else {
                items(nonPinnedConversations, key = { it.first }) { (id, summary) ->
                    ConversationDrawerRow(
                        id = id,
                        summary = summary,
                        onClick = { onSelectConversation(id) },
                        onLongClick = { contextConversationTarget = id to summary }
                    )
                }
            }
        }
    }

    // Render dialogues
    if (showCreateDialog) {
        CreateProjectDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { title, status ->
                viewModel.createProject(title, status)
                showCreateDialog = false
            }
        )
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

    if (showExplorerDialog) {
        ProjectsExplorerDialog(
            projects = projects,
            initialFilter = explorerInitialFilter,
            onDismiss = { showExplorerDialog = false },
            onSelect = { proj ->
                onSelectProject(proj)
                showExplorerDialog = false
            },
            onEdit = { proj ->
                showExplorerDialog = false
                editProjectTarget = proj
            }
        )
    }

    contextConversationTarget?.let { (id, summary) ->
        val isPinned = id in pinnedConversationIds
        AlertDialog(
            onDismissRequest = { contextConversationTarget = null },
            title = { Text(UiUtils.displayTitle(summary), fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.togglePinConversation(id)
                                contextConversationTarget = null
                            }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Icons.Default.PushPin, null, tint = PortaPrimary, modifier = Modifier.size(20.dp))
                        Text(if (isPinned) "Unpin Conversation" else "Pin Conversation", fontSize = 14.sp)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.deleteConversation(id)
                                contextConversationTarget = null
                            }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Icons.Default.Delete, null, tint = PortaError, modifier = Modifier.size(20.dp))
                        Text("Delete Chat", color = PortaError, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { contextConversationTarget = null }) {
                    Text("Close", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            },
            shape = RoundedCornerShape(14.dp)
        )
    }
}

@Composable
private fun CategoryHeaderRow(
    title: String,
    count: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconColor: Color,
    collapsed: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = if (collapsed) "▸" else "▾",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(10.dp)
        )
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(15.dp)
        )
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = count.toString(),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        )
    }
}

@Composable
private fun ProjectRow(
    project: Project,
    isActive: Boolean,
    repoName: String?,
    onClick: () -> Unit,
    onEditClick: () -> Unit
) {
    val isSpinner = project.status == ProjectStatus.IN_PROGRESS && isActive

    // Card background highlight state
    val containerColor = when {
        isActive -> PortaPrimary.copy(alpha = 0.12f)
        else -> Color.Transparent
    }
    val borderColor = when {
        isActive -> PortaPrimary.copy(alpha = 0.25f)
        else -> Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(containerColor)
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Project Title and Subtitle
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = project.title,
                fontSize = 13.sp,
                fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal,
                color = if (isActive) PortaPrimary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (repoName != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = repoName,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.width(6.dp))

        // Metadata indicator
        if (isSpinner) {
            val infiniteTransition = rememberInfiniteTransition(label = "spin")
            val rotationAngle by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1200, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "rotation"
            )
            Icon(
                imageVector = Icons.Default.Autorenew,
                contentDescription = null,
                tint = PortaPrimary,
                modifier = Modifier
                    .size(13.dp)
                    .graphicsLayer { rotationZ = rotationAngle }
            )
        } else if (project.hasIndicator) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(PortaTertiary)
            )
        } else if (project.timeBadge != null) {
            Text(
                text = project.timeBadge,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                modifier = Modifier
                    .background(
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                        shape = RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            )
        } else {
            // Edit trigger
            IconButton(
                onClick = onEditClick,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Edit project",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationDrawerRow(
    id: String,
    summary: JsonObject,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val title = remember(summary) { UiUtils.displayTitle(summary) }
    val stepCount = remember(summary) { summary.get("stepCount")?.asInt ?: 0 }
    val lastModified = remember(summary) { summary.get("lastModifiedTime")?.asString }
    val isRunning = remember(summary) { summary.get("status")?.asString == "CASCADE_RUN_STATUS_RUNNING" }
    val workspaceName = remember(summary) { UiUtils.extractWorkspaceName(summary) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (workspaceName != "Others") {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = workspaceName,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = UiUtils.relativeTime(lastModified),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
                Text(
                    text = "·",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
                Text(
                    text = "$stepCount steps",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }
        }
        if (isRunning) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.5.dp,
                color = PortaTertiary
            )
        }
    }
}

// ── CREATE DIALOG ──
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateProjectDialog(
    onDismiss: () -> Unit,
    onCreate: (String, ProjectStatus) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var selectedStatus by remember { mutableStateOf(ProjectStatus.IDLE) }
    var dropdownExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create New Project", fontSize = 18.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Project Title") },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PortaPrimary,
                        focusedLabelColor = PortaPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                ExposedDropdownMenuBox(
                    expanded = dropdownExpanded,
                    onExpandedChange = { dropdownExpanded = it },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = when (selectedStatus) {
                            ProjectStatus.IDLE -> "Idle"
                            ProjectStatus.IN_PROGRESS -> "In Progress"
                            ProjectStatus.BLOCKED -> "Blocked"
                        },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Status") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded) },
                        modifier = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable, true)
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = dropdownExpanded,
                        onDismissRequest = { dropdownExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Idle") },
                            onClick = {
                                selectedStatus = ProjectStatus.IDLE
                                dropdownExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("In Progress") },
                            onClick = {
                                selectedStatus = ProjectStatus.IN_PROGRESS
                                dropdownExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Blocked") },
                            onClick = {
                                selectedStatus = ProjectStatus.BLOCKED
                                dropdownExpanded = false
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (title.isNotBlank()) {
                        onCreate(title, selectedStatus)
                    }
                },
                enabled = title.isNotBlank()
            ) {
                Text("Create", color = PortaPrimary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        },
        shape = RoundedCornerShape(14.dp)
    )
}

// ── EDIT DIALOG ──
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditProjectDialog(
    project: Project,
    onDismiss: () -> Unit,
    onSave: (String, ProjectStatus) -> Unit,
    onDelete: () -> Unit
) {
    var title by remember { mutableStateOf(project.title) }
    var selectedStatus by remember { mutableStateOf(project.status) }
    var dropdownExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Project Settings", fontSize = 18.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Project Title") },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PortaPrimary,
                        focusedLabelColor = PortaPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                ExposedDropdownMenuBox(
                    expanded = dropdownExpanded,
                    onExpandedChange = { dropdownExpanded = it },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = when (selectedStatus) {
                            ProjectStatus.IDLE -> "Idle"
                            ProjectStatus.IN_PROGRESS -> "In Progress"
                            ProjectStatus.BLOCKED -> "Blocked"
                        },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Status") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded) },
                        modifier = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable, true)
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = dropdownExpanded,
                        onDismissRequest = { dropdownExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Idle") },
                            onClick = {
                                selectedStatus = ProjectStatus.IDLE
                                dropdownExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("In Progress") },
                            onClick = {
                                selectedStatus = ProjectStatus.IN_PROGRESS
                                dropdownExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Blocked") },
                            onClick = {
                                selectedStatus = ProjectStatus.BLOCKED
                                dropdownExpanded = false
                            }
                        )
                    }
                }

                // Delete Action button
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onDelete)
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, tint = PortaError, modifier = Modifier.size(18.dp))
                    Text("Delete Project", color = PortaError, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (title.isNotBlank()) {
                        onSave(title, selectedStatus)
                    }
                },
                enabled = title.isNotBlank()
            ) {
                Text("Save", color = PortaPrimary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        },
        shape = RoundedCornerShape(14.dp)
    )
}

// ── FULLSCREEN EXPLORER DIALOG ──
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectsExplorerDialog(
    projects: List<Project>,
    initialFilter: ProjectStatus?,
    onDismiss: () -> Unit,
    onSelect: (Project) -> Unit,
    onEdit: (Project) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var filterStatus by remember { mutableStateOf<ProjectStatus?>(initialFilter) }
    var sortBy by remember { mutableStateOf("newest") } // newest, oldest, alpha

    // Sort option expansions
    var sortDropdownExpanded by remember { mutableStateOf(false) }

    val filteredList = remember(projects, searchQuery, filterStatus, sortBy) {
        var res = projects
        if (searchQuery.isNotBlank()) {
            res = res.filter { it.title.contains(searchQuery, ignoreCase = true) }
        }
        if (filterStatus != null) {
            res = res.filter { it.status == filterStatus }
        }
        when (sortBy) {
            "newest" -> res.sortedByDescending { it.lastUpdated }
            "oldest" -> res.sortedBy { it.lastUpdated }
            "alpha" -> res.sortedBy { it.title }
            else -> res
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                // Top header bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Projects Explorer",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                // Search Box
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search projects...", fontSize = 14.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(18.dp)) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, null, Modifier.size(18.dp))
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PortaPrimary,
                        cursorColor = PortaPrimary
                    )
                )

                // Category chips & sort button row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Category Filters
                    FilterChip(
                        selected = filterStatus == null,
                        onClick = { filterStatus = null },
                        label = { Text("All") }
                    )
                    FilterChip(
                        selected = filterStatus == ProjectStatus.BLOCKED,
                        onClick = { filterStatus = ProjectStatus.BLOCKED },
                        label = { Text("Blocked") }
                    )
                    FilterChip(
                        selected = filterStatus == ProjectStatus.IN_PROGRESS,
                        onClick = { filterStatus = ProjectStatus.IN_PROGRESS },
                        label = { Text("In Progress") }
                    )
                    FilterChip(
                        selected = filterStatus == ProjectStatus.IDLE,
                        onClick = { filterStatus = ProjectStatus.IDLE },
                        label = { Text("Idle") }
                    )

                    Spacer(Modifier.weight(1f))

                    // Sort button trigger
                    Box {
                        TextButton(
                            onClick = { sortDropdownExpanded = true },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Icon(Icons.Default.Sort, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                when (sortBy) {
                                    "newest" -> "Newest"
                                    "oldest" -> "Oldest"
                                    "alpha" -> "A-Z"
                                    else -> "Sort"
                                },
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        DropdownMenu(
                            expanded = sortDropdownExpanded,
                            onDismissRequest = { sortDropdownExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Last Updated (Newest)") },
                                onClick = {
                                    sortBy = "newest"
                                    sortDropdownExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Last Updated (Oldest)") },
                                onClick = {
                                    sortBy = "oldest"
                                    sortDropdownExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Alphabetical (A-Z)") },
                                onClick = {
                                    sortBy = "alpha"
                                    sortDropdownExpanded = false
                                }
                            )
                        }
                    }
                }

                // Grid list of items
                if (filteredList.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.SearchOff, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))
                            Text("No projects found", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredList, key = { it.id }) { proj ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(proj) },
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = proj.title,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            // Status tag
                                            val tagColor = when (proj.status) {
                                                ProjectStatus.BLOCKED -> PortaError
                                                ProjectStatus.IN_PROGRESS -> PortaWarning
                                                ProjectStatus.IDLE -> PortaSuccess
                                            }
                                            Box(
                                                modifier = Modifier
                                                    .background(tagColor.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = when (proj.status) {
                                                        ProjectStatus.BLOCKED -> "Blocked"
                                                        ProjectStatus.IN_PROGRESS -> "In Progress"
                                                        ProjectStatus.IDLE -> "Idle"
                                                    },
                                                    color = tagColor,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }

                                            // Time badge
                                            if (proj.timeBadge != null) {
                                                Text(
                                                    text = "·  ${proj.timeBadge}",
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                                )
                                            }
                                        }
                                    }

                                    IconButton(onClick = { onEdit(proj) }) {
                                        Icon(
                                            Icons.Default.Settings,
                                            contentDescription = "Edit project",
                                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
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

private fun getProjectRepoName(projTitle: String, conversations: List<Pair<String, JsonObject>>): String? {
    val normProject = UiUtils.normalizeTitle(projTitle)
    val matched = conversations.find {
        val title = UiUtils.displayTitle(it.second)
        val normTitle = UiUtils.normalizeTitle(title)
        normTitle.isNotEmpty() && normTitle == normProject
    }
    if (matched != null) {
        val repo = UiUtils.extractWorkspaceName(matched.second)
        if (repo != "Others") return repo
    }
    val titleLower = projTitle.lowercase()
    if (titleLower.contains("porta")) return "porta"
    if (titleLower.contains("odoo")) return "odoo-addons"
    return null
}
