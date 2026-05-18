package dev.spola.app.app.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.spola.app.app.theme.GolemColors

/**
 * Simple shimmer-like loading skeleton component.
 * Shows animated placeholder bars to indicate content is loading.
 *
 * Usage:
 *   LoadingSkeleton() // single row skeleton
 *   LoadingSkeletonRow() // chat message skeleton
 *   LoadingSkeletonList(count = 5) // multiple rows
 */
@Composable
fun LoadingSkeleton(
    modifier: Modifier = Modifier,
) {
    val shimmerAlpha by shimmerAnimation()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(16.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(GolemColors.bgElevated.copy(alpha = shimmerAlpha)),
    )
}

@Composable
fun LoadingSkeletonRow(
    modifier: Modifier = Modifier,
) {
    val shimmerAlpha by shimmerAnimation()

    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Avatar circle
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(GolemColors.bgElevated.copy(alpha = shimmerAlpha)),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .height(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(GolemColors.bgElevated.copy(alpha = shimmerAlpha)),
            )
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(GolemColors.bgElevated.copy(alpha = shimmerAlpha)),
            )
        }
    }
}

@Composable
fun LoadingSkeletonList(
    count: Int = 3,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        repeat(count) {
            LoadingSkeletonRow()
            if (it < count - 1) {
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
fun LoadingSkeletonCard(
    modifier: Modifier = Modifier,
    height: Dp = 100.dp,
) {
    val shimmerAlpha by shimmerAnimation()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(10.dp))
            .background(GolemColors.bgElevated.copy(alpha = shimmerAlpha)),
    )
}

/**
 * Reusable shimmer animation that oscillates alpha between 0.3 and 0.7.
 * Returns a State<Float> that can be observed for recomposition via `by` delegation.
 */
@Composable
private fun shimmerAnimation(): State<Float> {
    val transition = rememberInfiniteTransition(label = "shimmer")
    return transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "shimmerAlpha",
    )
}
