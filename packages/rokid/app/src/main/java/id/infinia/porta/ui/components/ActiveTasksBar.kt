package id.infinia.porta.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.infinia.porta.shared.protocol.ChatMessage
import id.infinia.porta.shared.protocol.getActiveTasks
import id.infinia.porta.shared.protocol.getActiveSubagents
import id.infinia.porta.ui.theme.*

/**
 * Collapsible status bar showing running background tasks and active subagents.
 *
 * Displays above the chat message list when there are active items.
 * Tap to expand/collapse the detail view with individual task/subagent entries.
 */
@Composable
fun ActiveTasksBar(chatMessages: List<ChatMessage>) {
    val activeTasks = remember(chatMessages) { getActiveTasks(chatMessages) }
    val activeSubagents = remember(chatMessages) { getActiveSubagents(chatMessages) }

    val totalTasks = activeTasks.size
    val totalSubagents = activeSubagents.sumOf { it.count }
    val hasActive = totalTasks > 0 || totalSubagents > 0

    // Only show when there are active items
    AnimatedVisibility(
        visible = hasActive,
        enter = slideInVertically() + fadeIn(),
        exit = slideOutVertically() + fadeOut()
    ) {
        var expanded by remember { mutableStateOf(false) }
        val accentPurple = Color(0xFFA78BFA)

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            tonalElevation = 1.dp
        ) {
            Column {
                // ── Compact header ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Animated spinning indicator
                    val infiniteTransition = rememberInfiniteTransition(label = "barSpin")
                    val rotation by infiniteTransition.animateFloat(
                        initialValue = 0f,
                        targetValue = 360f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1500, easing = LinearEasing)
                        ),
                        label = "barSpinRotation"
                    )
                    Icon(
                        Icons.Default.Sync,
                        contentDescription = "Active",
                        modifier = Modifier
                            .size(14.dp)
                            .rotate(rotation),
                        tint = PortaTertiary
                    )

                    // Summary text
                    val parts = mutableListOf<String>()
                    if (totalTasks > 0) {
                        parts.add("$totalTasks task${if (totalTasks != 1) "s" else ""}")
                    }
                    if (totalSubagents > 0) {
                        parts.add("$totalSubagents subagent${if (totalSubagents != 1) "s" else ""}")
                    }
                    Text(
                        parts.joinToString(" • ") + " running",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.weight(1f)
                    )

                    // Expand/collapse indicator
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }

                // ── Expanded detail list ──
                AnimatedVisibility(
                    visible = expanded,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(
                        modifier = Modifier.padding(
                            start = 14.dp, end = 14.dp, bottom = 8.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Running tasks
                        activeTasks.forEach { task ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(PortaTertiary.copy(alpha = 0.06f))
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Pulsing dot
                                val pulseTransition = rememberInfiniteTransition(label = "taskPulse${task.stepIndex}")
                                val pulseAlpha by pulseTransition.animateFloat(
                                    initialValue = 1f,
                                    targetValue = 0.3f,
                                    animationSpec = infiniteRepeatable(
                                        animation = tween(800, easing = EaseInOutSine),
                                        repeatMode = RepeatMode.Reverse
                                    ),
                                    label = "taskPulseAlpha"
                                )
                                Box(
                                    Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(PortaTertiary.copy(alpha = pulseAlpha))
                                )

                                Icon(
                                    Icons.Default.Terminal,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = PortaTertiary.copy(alpha = 0.7f)
                                )
                                Text(
                                    "$ ${task.commandLine}",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    "running",
                                    fontSize = 9.sp,
                                    color = PortaTertiary.copy(alpha = 0.5f)
                                )
                            }
                        }

                        // Active subagents
                        activeSubagents.forEach { sa ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(accentPurple.copy(alpha = 0.06f))
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Spinning icon
                                val saTransition = rememberInfiniteTransition(label = "saSpin${sa.stepIndex}")
                                val saRotation by saTransition.animateFloat(
                                    initialValue = 0f,
                                    targetValue = 360f,
                                    animationSpec = infiniteRepeatable(
                                        animation = tween(1200, easing = LinearEasing)
                                    ),
                                    label = "saSpinRotation"
                                )
                                Icon(
                                    Icons.Default.Sync,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(12.dp)
                                        .rotate(saRotation),
                                    tint = accentPurple.copy(alpha = 0.7f)
                                )

                                // Type badge
                                Surface(
                                    shape = RoundedCornerShape(3.dp),
                                    color = accentPurple
                                ) {
                                    Text(
                                        sa.typeName.uppercase(),
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White,
                                        modifier = Modifier.padding(
                                            horizontal = 4.dp, vertical = 1.dp
                                        )
                                    )
                                }

                                Text(
                                    sa.role,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )

                                if (sa.count > 1) {
                                    Text(
                                        "×${sa.count}",
                                        fontSize = 9.sp,
                                        color = accentPurple.copy(alpha = 0.6f)
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
