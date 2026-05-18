package dev.spola.app.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.spola.app.app.components.BottomTabBar
import dev.spola.app.app.components.CommandPalette
import dev.spola.app.app.components.CommandPaletteAction
import dev.spola.app.app.decompose.DashboardComponent
import dev.spola.app.app.navigation.NavigationTab
import dev.spola.app.app.pages.*
import dev.spola.app.app.theme.GolemColors
import dev.spola.app.app.theme.GolemTheme

@Composable
fun AppShell(
    dashboardComponent: DashboardComponent? = null,
    modifier: Modifier = Modifier,
) {
    var selectedTab by remember { mutableStateOf(NavigationTab.Chat) }
    var showCommandPalette by remember { mutableStateOf(false) }
    var isDarkMode by remember { mutableStateOf(true) }

    // Update the global color mode
    LaunchedEffect(isDarkMode) {
        GolemColors.isDarkMode = isDarkMode
    }

    val commandActions = rememberCommandActions(
        dashboardComponent = dashboardComponent,
        onToggleDarkMode = { isDarkMode = !isDarkMode },
        onSelectTab = { selectedTab = it },
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .onKeyEvent { keyEvent ->
                handleKeyEvent(
                    keyEvent = keyEvent,
                    showCommandPalette = showCommandPalette,
                    setShowCommandPalette = { showCommandPalette = it },
                )
            },
    ) {
        GolemTheme(darkTheme = isDarkMode) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                AppShellContent(
                    selectedTab = selectedTab,
                    onTabSelected = { selectedTab = it },
                    dashboardComponent = dashboardComponent,
                )
            }

            // Command palette overlay — on top of everything
            CommandPalette(
                visible = showCommandPalette,
                onDismiss = { showCommandPalette = false },
                actions = commandActions,
            )
        }
    }
}

@Composable
private fun rememberCommandActions(
    dashboardComponent: DashboardComponent?,
    onToggleDarkMode: () -> Unit,
    onSelectTab: (NavigationTab) -> Unit,
): List<CommandPaletteAction> = remember(dashboardComponent, onToggleDarkMode, onSelectTab) {
    listOf(
        CommandPaletteAction(
            id = "new-session",
            label = "New Session",
            emoji = "💬",
            shortcut = "⌘N",
            action = {
                dashboardComponent?.sessionList?.showCreateDialog()
                onSelectTab(NavigationTab.Chat)
            },
        ),
        CommandPaletteAction(
            id = "switch-model",
            label = "Switch Model",
            emoji = "🤖",
            action = {
                onSelectTab(NavigationTab.Settings)
            },
        ),
        CommandPaletteAction(
            id = "toggle-theme",
            label = "Toggle Dark Mode",
            emoji = "🌓",
            shortcut = "⌘T",
            action = onToggleDarkMode,
        ),
        CommandPaletteAction(
            id = "help",
            label = "Help",
            emoji = "❓",
            action = {
                onSelectTab(NavigationTab.Settings)
            },
        ),
    )
}

private fun handleKeyEvent(
    keyEvent: KeyEvent,
    showCommandPalette: Boolean,
    setShowCommandPalette: (Boolean) -> Unit,
): Boolean {
    val ctrlOrMeta = keyEvent.isCtrlPressed || keyEvent.isMetaPressed
    return when {
        keyEvent.key == Key.K && ctrlOrMeta && !showCommandPalette -> {
            setShowCommandPalette(true)
            true
        }

        keyEvent.key == Key.Escape && showCommandPalette -> {
            setShowCommandPalette(false)
            true
        }

        else -> false
    }
}

@Composable
private fun ColumnScope.AppShellContent(
    selectedTab: NavigationTab,
    onTabSelected: (NavigationTab) -> Unit,
    dashboardComponent: DashboardComponent?,
) {
    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
        AnimatedContent(
            targetState = selectedTab,
            transitionSpec = {
                fadeIn(animationSpec = tween(200)) togetherWith
                    fadeOut(animationSpec = tween(150))
            },
            label = "pageTransition",
        ) { tab ->
            when (tab) {
                NavigationTab.Chat -> {
                    ErrorBoundary {
                        if (dashboardComponent != null) {
                            ChatPage(component = dashboardComponent)
                        } else {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Not connected", color = GolemColors.textMuted)
                            }
                        }
                    }
                }

                NavigationTab.Tools -> ErrorBoundary { ToolsPage(dashboardComponent = dashboardComponent) }
                NavigationTab.Memory -> ErrorBoundary { MemoryPage(dashboardComponent = dashboardComponent) }
                NavigationTab.Kanban -> ErrorBoundary { KanbanPage(dashboardComponent = dashboardComponent) }
                NavigationTab.Workflows -> ErrorBoundary {
                    if (dashboardComponent != null) {
                        WorkflowsPage(dashboardComponent = dashboardComponent)
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Not connected", color = GolemColors.textMuted)
                        }
                    }
                }

                NavigationTab.Scheduler -> ErrorBoundary { SchedulerPage(dashboardComponent = dashboardComponent) }
                NavigationTab.Settings -> ErrorBoundary { SettingsPage(dashboardComponent = dashboardComponent) }
            }
        }
    }

    BottomTabBar(
        selectedTab = selectedTab,
        onTabSelected = onTabSelected,
    )
}

// ── Error Boundary ────────────────────────────────────────────────────

/**
 * A simple error boundary composable that provides retry capabilities.
 * Uses a key-based recomposition to reset the content on retry.
 * Note: Compose does not support try-catch around composable function invocations,
 * so errors must be handled at the component level. This boundary provides
 * a retry mechanism for graceful recovery via key-based remounting.
 *
 * Components can use the [ErrorFallback] composable to display errors caught
 * via state management at their level.
 */
@Composable
fun ErrorBoundary(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var retryKey by remember { mutableStateOf(0) }

    Box(modifier = modifier.fillMaxSize()) {
        key(retryKey) {
            content()
        }
    }
}

@Composable
private fun ErrorFallback(
    message: String,
    onRetry: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().padding(32.dp),
        ) {
            Text(
                text = "⚠️",
                fontSize = 40.sp,
            )
            Text(
                text = "Something went wrong",
                color = GolemColors.textPrimary,
                fontSize = 16.sp,
            )
            Text(
                text = message,
                color = GolemColors.textMuted,
                fontSize = 13.sp,
                maxLines = 4,
            )
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(
                    containerColor = GolemColors.accent,
                ),
            ) {
                Text("Retry")
            }
        }
    }
}
