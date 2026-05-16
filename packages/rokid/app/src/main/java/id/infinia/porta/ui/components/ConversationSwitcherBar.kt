package id.infinia.porta.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
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

/**
 * Horizontally scrollable conversation switcher bar.
 *
 * Shows active conversations grouped by workspace as compact chips,
 * allowing users to quick-switch without leaving the chat screen.
 *
 * Features:
 * - Workspace-colored section headers
 * - Running indicator (spinner) on active agent conversations
 * - Current conversation highlighted with border
 * - Tap to switch, long-press shows full title
 * - Collapse/expand toggle
 */

data class ConvoChip(
    val id: String,
    val title: String,
    val workspace: String,
    val isRunning: Boolean,
    val stepCount: Int,
    val lastModified: String?
)

@Composable
fun ConversationSwitcherBar(
    conversations: Map<String, JsonObject>,
    currentConversationId: String?,
    onSelectConversation: (String) -> Unit,
    onNewConversation: () -> Unit,
    onOpenFullList: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (conversations.isEmpty()) return

    // Group conversations by workspace
    val workspaceGroups = remember(conversations) {
        val map = mutableMapOf<String, MutableList<ConvoChip>>()
        for ((id, summary) in conversations) {
            val wsName = extractWsName(summary)
            val chip = ConvoChip(
                id = id,
                title = summary.get("summary")?.asString ?: id.take(8) + "…",
                workspace = wsName,
                isRunning = summary.get("status")?.asString == "CASCADE_RUN_STATUS_RUNNING",
                stepCount = summary.get("stepCount")?.asInt ?: 0,
                lastModified = summary.get("lastModifiedTime")?.asString
            )
            map.getOrPut(wsName) { mutableListOf() }.add(chip)
        }
        // Sort: running workspaces first, then most recently modified
        map.entries
            .filter { it.key != "Others" }
            .map { (name, chips) ->
                chips.sortByDescending { it.lastModified ?: "" }
                name to chips.toList()
            }
            .sortedWith(
                compareByDescending<Pair<String, List<ConvoChip>>> { (_, chips) ->
                    chips.any { it.isRunning }
                }.thenByDescending { (_, chips) ->
                    chips.maxOfOrNull { it.lastModified ?: "" } ?: ""
                }
            )
    }

    val scrollState = rememberScrollState()

    // Auto-scroll to active conversation chip
    val activeIndex = remember(currentConversationId, workspaceGroups) {
        var idx = 0
        for ((_, chips) in workspaceGroups) {
            idx++ // workspace label
            for (chip in chips) {
                if (chip.id == currentConversationId) return@remember idx
                idx++
            }
        }
        -1
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scrollState)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                workspaceGroups.forEach { (wsName, chips) ->
                    // Workspace label
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        modifier = Modifier.padding(end = 2.dp)
                    ) {
                        Icon(
                            Icons.Default.Folder,
                            contentDescription = null,
                            modifier = Modifier.size(10.dp),
                            tint = PortaTertiary.copy(alpha = 0.7f)
                        )
                        Text(
                            wsName,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = PortaTertiary.copy(alpha = 0.7f),
                            maxLines = 1
                        )
                    }

                    // Conversation chips for this workspace
                    chips.forEach { chip ->
                        ConvoChipItem(
                            chip = chip,
                            isActive = chip.id == currentConversationId,
                            onClick = { onSelectConversation(chip.id) }
                        )
                    }

                    // Separator between workspace groups
                    if (workspaceGroups.last().first != wsName) {
                        Box(
                            Modifier
                                .width(1.dp)
                                .height(20.dp)
                                .background(
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                                )
                        )
                    }
                }

                // New conversation + full list buttons
                SuggestionChip(
                    onClick = onNewConversation,
                    label = {
                        Icon(
                            Icons.Default.Add, null,
                            modifier = Modifier.size(14.dp)
                        )
                    },
                    modifier = Modifier.height(28.dp),
                    shape = CircleShape,
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )

                SuggestionChip(
                    onClick = onOpenFullList,
                    label = {
                        Icon(
                            Icons.AutoMirrored.Filled.ViewList, null,
                            modifier = Modifier.size(14.dp)
                        )
                    },
                    modifier = Modifier.height(28.dp),
                    shape = CircleShape,
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
            }

            // Subtle bottom divider
            HorizontalDivider(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                thickness = 1.dp
            )
        }
    }
}

@Composable
private fun ConvoChipItem(
    chip: ConvoChip,
    isActive: Boolean,
    onClick: () -> Unit
) {
    val chipShape = RoundedCornerShape(14.dp)

    Surface(
        modifier = Modifier
            .height(28.dp)
            .clip(chipShape)
            .then(
                if (isActive) Modifier.border(
                    width = 1.5.dp,
                    color = PortaPrimary,
                    shape = chipShape
                ) else Modifier
            )
            .clickable(onClick = onClick),
        shape = chipShape,
        color = when {
            isActive -> PortaPrimary.copy(alpha = 0.15f)
            chip.isRunning -> PortaTertiary.copy(alpha = 0.08f)
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        tonalElevation = if (isActive) 4.dp else 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            // Running spinner or status dot
            if (chip.isRunning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(10.dp),
                    strokeWidth = 1.5.dp,
                    color = PortaTertiary
                )
            } else if (isActive) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(PortaPrimary)
                )
            }

            // Title
            Text(
                chip.title,
                fontSize = 11.sp,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                color = when {
                    isActive -> PortaPrimary
                    chip.isRunning -> PortaTertiary
                    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 140.dp)
            )

            // Step count badge
            if (chip.stepCount > 0) {
                Text(
                    "${chip.stepCount}",
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                )
            }
        }
    }
}

private fun extractWsName(summary: JsonObject): String {
    val workspaces = summary.getAsJsonArray("workspaces")
    if (workspaces == null || workspaces.size() == 0) return "Others"
    val ws = workspaces[0].asJsonObject
    val repo = ws.getAsJsonObject("repository")?.get("computedName")?.asString
    if (repo != null) return repo.substringAfterLast("/")
    val uri = ws.get("workspaceFolderAbsoluteUri")?.asString
    if (uri != null) return uri.substringAfterLast("/")
    return "Others"
}
