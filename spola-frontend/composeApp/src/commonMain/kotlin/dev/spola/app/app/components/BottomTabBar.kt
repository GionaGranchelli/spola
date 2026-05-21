package dev.spola.app.app.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.spola.app.app.navigation.NavigationTab
import dev.spola.app.app.theme.SpolaColors

/**
 * Floating bottom tab bar inspired by OpenHuman's design.
 *
 * A modern pill-shaped tab bar fixed at the bottom of the screen.
 * Tabs show emoji icons with labels that appear on hover.
 * The active tab is highlighted with the accent color.
 */
@Composable
fun BottomTabBar(
    selectedTab: NavigationTab,
    onTabSelected: (NavigationTab) -> Unit,
    modifier: Modifier = Modifier,
    tabs: List<NavigationTab> = NavigationTab.entries.toList(),
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val showLabels = maxWidth >= 480.dp

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(SpolaColors.bgSurface.copy(alpha = 0.92f))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEach { tab ->
                    TabItem(
                        tab = tab,
                        isSelected = tab == selectedTab,
                        showLabel = showLabels,
                        onClick = { onTabSelected(tab) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TabItem(
    tab: NavigationTab,
    isSelected: Boolean,
    showLabel: Boolean,
    onClick: () -> Unit,
) {
    val bgColor by animateColorAsState(
        targetValue = if (isSelected) SpolaColors.accent.copy(alpha = 0.2f) else Color.Transparent,
        animationSpec = tween(200),
        label = "tabBg",
    )
    val contentColor by animateColorAsState(
        targetValue = if (isSelected) SpolaColors.accent else SpolaColors.tabInactive,
        animationSpec = tween(200),
        label = "tabContent",
    )

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .semantics {
                contentDescription = "${tab.label} tab — ${tab.description}"
                role = Role.Tab
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (showLabel) 6.dp else 0.dp),
        ) {
            Text(
                text = tab.emoji,
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
            )
            if (showLabel) {
                Text(
                    text = tab.label,
                    color = contentColor,
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
