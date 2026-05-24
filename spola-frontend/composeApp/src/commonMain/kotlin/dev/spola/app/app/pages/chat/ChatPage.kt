package dev.spola.app.app.pages

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.spola.app.app.decompose.DashboardComponent
import dev.spola.app.app.theme.SpolaColors

@Composable
fun ChatPage(
    component: DashboardComponent,
    modifier: Modifier = Modifier,
) {
    val viewModel = rememberChatViewModel(component)
    val state = viewModel.state
    var sidebarExpanded by remember { mutableStateOf(true) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(SpolaColors.bg),
    ) {
        val useOverlaySidebar = maxWidth < 720.dp
        val sidebar: @Composable (Modifier) -> Unit = { sidebarModifier ->
            ChatSidebar(
                sessions = state.sessions,
                selectedSessionId = state.selectedSessionId,
                isLoading = state.isLoading,
                models = state.models,
                onSelectSession = {
                    viewModel.selectSession(it)
                    if (useOverlaySidebar) sidebarExpanded = false
                },
                onNewSession = {
                    viewModel.showCreateDialog()
                    if (useOverlaySidebar) sidebarExpanded = false
                },
                onDeleteSession = viewModel::deleteSession,
                onChangeModel = viewModel::updateSessionModel,
                modifier = sidebarModifier,
            )
        }

        if (useOverlaySidebar) {
            ChatOverlayLayout(
                state = state,
                sidebarExpanded = sidebarExpanded,
                onSidebarExpandedChange = { sidebarExpanded = it },
                sidebar = sidebar,
                onGoalChange = viewModel::updateGoal,
                onSend = viewModel::startRun,
                onModelSelected = viewModel::updateSelectedSessionModel,
            )
        } else {
            ChatDesktopLayout(
                state = state,
                sidebarExpanded = sidebarExpanded,
                onSidebarExpandedChange = { sidebarExpanded = it },
                sidebar = sidebar,
                onGoalChange = viewModel::updateGoal,
                onSend = viewModel::startRun,
                onModelSelected = viewModel::updateSelectedSessionModel,
            )
        }
    }

    if (state.createDialogVisible) {
        ChatCreateSessionDialog(component = component.sessionList)
    }
}

@Composable
private fun TokenUsageBar(
    turnTokens: String,
    cumulativeTokens: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .background(SpolaColors.bgElevated)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(
            text = "[$turnTokens]",
            color = SpolaColors.textMuted,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "[$cumulativeTokens]",
            color = SpolaColors.textMuted,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun ChatDesktopLayout(
    state: ChatPageState,
    sidebarExpanded: Boolean,
    onSidebarExpandedChange: (Boolean) -> Unit,
    sidebar: @Composable (Modifier) -> Unit,
    onGoalChange: (String) -> Unit,
    onSend: () -> Unit,
    onModelSelected: (String) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val occupiedWidth = if (sidebarExpanded) 281.dp else 33.dp
        val contentWidth = if (maxWidth > occupiedWidth) maxWidth - occupiedWidth else maxWidth

        Row(modifier = Modifier.fillMaxSize()) {
            AnimatedVisibility(
                visible = sidebarExpanded,
                enter = slideInHorizontally { -it } + fadeIn(),
                exit = slideOutHorizontally { -it } + fadeOut(),
            ) {
                sidebar(Modifier.width(280.dp).fillMaxHeight())
            }

            if (!sidebarExpanded) {
                Box(
                    modifier = Modifier
                        .width(32.dp)
                        .fillMaxHeight()
                        .clickable { onSidebarExpandedChange(true) }
                        .semantics {
                            contentDescription = "Expand sidebar"
                            role = Role.Button
                        }
                        .padding(8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("☰", color = SpolaColors.textMuted, fontSize = 16.sp)
                }
            }

            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(SpolaColors.bgElevated),
            )

            ChatContent(
                state = state,
                onGoalChange = onGoalChange,
                onSend = onSend,
                onToggleSidebar = { onSidebarExpandedChange(!sidebarExpanded) },
                onModelSelected = onModelSelected,
                modifier = Modifier
                    .width(contentWidth)
                    .fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun ChatOverlayLayout(
    state: ChatPageState,
    sidebarExpanded: Boolean,
    onSidebarExpandedChange: (Boolean) -> Unit,
    sidebar: @Composable (Modifier) -> Unit,
    onGoalChange: (String) -> Unit,
    onSend: () -> Unit,
    onModelSelected: (String) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        ChatContent(
            state = state,
            onGoalChange = onGoalChange,
            onSend = onSend,
            onToggleSidebar = { onSidebarExpandedChange(!sidebarExpanded) },
            onModelSelected = onModelSelected,
            modifier = Modifier.fillMaxSize(),
        )

        AnimatedVisibility(
            visible = sidebarExpanded,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.matchParentSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .clickable { onSidebarExpandedChange(false) },
            )
        }

        AnimatedVisibility(
            visible = sidebarExpanded,
            enter = slideInHorizontally { -it } + fadeIn(),
            exit = slideOutHorizontally { -it } + fadeOut(),
            modifier = Modifier.fillMaxHeight(),
        ) {
            sidebar(
                Modifier
                    .fillMaxHeight()
                    .widthIn(max = 320.dp)
                    .fillMaxWidth(0.88f),
            )
        }
    }
}

@Composable
private fun ChatContent(
    state: ChatPageState,
    onGoalChange: (String) -> Unit,
    onSend: () -> Unit,
    onToggleSidebar: () -> Unit,
    onModelSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.imePadding()) {
        Column(Modifier.fillMaxSize()) {
            // Reserve space for ChatStatusBar overlay at the top
            Spacer(Modifier.height(49.dp))

            // Messages fill remaining space dynamically
            MessageList(
                messages = state.visibleMessages,
                isStreaming = state.isRunning,
                toolEvents = state.toolEvents,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )

            // Token usage bar — visible when token data is available
            val turnTokens = state.turnTokens
            val cumulativeTokens = state.cumulativeTokens
            if (turnTokens != null && cumulativeTokens != null) {
                TokenUsageBar(
                    turnTokens = turnTokens,
                    cumulativeTokens = cumulativeTokens,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Input bar at bottom of Column
            ChatInput(
                value = state.goal,
                onValueChange = onGoalChange,
                onSend = onSend,
                isRunning = state.isRunning,
                isSessionSelected = state.isSessionSelected,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Status bar overlay at top
        ChatStatusBar(
            modelId = state.selectedModelId,
            models = state.models,
            status = state.status,
            isConnected = true,
            onModelSelected = onModelSelected,
            onToggleSidebar = onToggleSidebar,
        )
    }
}
