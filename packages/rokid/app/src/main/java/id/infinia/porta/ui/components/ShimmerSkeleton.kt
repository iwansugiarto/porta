package id.infinia.porta.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Shimmer loading effect composables for skeleton screens.
 *
 * Provides animated placeholder content while data loads,
 * improving perceived performance and UX.
 */

@Composable
private fun shimmerBrush(): Brush {
    val shimmerColors = listOf(
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f),
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    )

    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateX by transition.animateFloat(
        initialValue = -300f,
        targetValue = 600f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerX"
    )

    return Brush.linearGradient(
        colors = shimmerColors,
        start = Offset(translateX, 0f),
        end = Offset(translateX + 300f, 50f)
    )
}

@Composable
fun ShimmerBox(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(6.dp)
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(shimmerBrush())
    )
}

/**
 * Skeleton for a conversation card — matches ConversationCard layout.
 */
@Composable
fun ConversationCardSkeleton(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icon placeholder
        ShimmerBox(
            modifier = Modifier.size(16.dp),
            shape = RoundedCornerShape(4.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // Title line
            ShimmerBox(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(14.dp)
            )
            // Subtitle line
            ShimmerBox(
                modifier = Modifier
                    .fillMaxWidth(0.4f)
                    .height(10.dp)
            )
        }
    }
}

/**
 * Skeleton for the conversation list — shows grouped placeholders.
 */
@Composable
fun ConversationListSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Workspace header skeleton
        ShimmerBox(
            modifier = Modifier
                .width(100.dp)
                .height(12.dp)
                .padding(vertical = 2.dp)
        )
        // 4 conversation card skeletons
        repeat(4) {
            ConversationCardSkeleton()
        }
        Spacer(Modifier.height(8.dp))
        // Second workspace header
        ShimmerBox(
            modifier = Modifier
                .width(80.dp)
                .height(12.dp)
                .padding(vertical = 2.dp)
        )
        repeat(2) {
            ConversationCardSkeleton()
        }
    }
}

/**
 * Skeleton for chat message bubbles while loading history.
 */
@Composable
fun ChatMessagesSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Assistant message (left-aligned)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ShimmerBox(Modifier.width(240.dp).height(14.dp))
                ShimmerBox(Modifier.width(200.dp).height(14.dp))
                ShimmerBox(Modifier.width(160.dp).height(14.dp))
            }
        }
        // User message (right-aligned)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            ShimmerBox(
                Modifier.width(180.dp).height(36.dp),
                shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
            )
        }
        // Assistant message
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ShimmerBox(Modifier.width(260.dp).height(14.dp))
                ShimmerBox(Modifier.width(220.dp).height(14.dp))
            }
        }
        // User message
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            ShimmerBox(
                Modifier.width(140.dp).height(36.dp),
                shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
            )
        }
        // Assistant message
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ShimmerBox(Modifier.width(200.dp).height(14.dp))
                ShimmerBox(Modifier.width(250.dp).height(14.dp))
                ShimmerBox(Modifier.width(180.dp).height(14.dp))
            }
        }
    }
}
