package dev.spola.app.app.pages

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import dev.spola.app.app.components.EmptyState
import dev.spola.app.app.components.LoadingSkeletonList
import dev.spola.app.app.components.ModelSelector
import dev.spola.app.app.decompose.DashboardComponent
import dev.spola.app.app.theme.GolemColors
import dev.spola.app.models.Message
import dev.spola.app.models.MessageRole
import dev.spola.app.models.ModelInfo
import dev.spola.app.models.StreamEvent
import dev.spola.app.models.StreamEventType
import dev.spola.app.state.currentTimeMillis

/**
 * Modern Slack-like ChatPage with session sidebar, message bubbles, streaming, and model selection.
 *
 * Left sidebar: collapsible session list
 * Right area: status bar, scrollable messages, input bar
 */
@Composable
fun ChatPage(
    component: DashboardComponent,
    modifier: Modifier = Modifier,
) {
    var sidebarExpanded by remember { mutableStateOf(true) }
    val sessions by component.sessionList.sessions.subscribeAsState()
    val selectedSessionId by component.sessionList.selectedSessionId.subscribeAsState()
    val models by component.sessionList.availableModels.subscribeAsState()
    val isLoading by component.sessionList.isLoading.collectAsState()
    val createDialogVisible by component.sessionList.createDialogVisible.collectAsState()

    // Agent run state
    val goal by component.agentRun.goal.subscribeAsState()
    val conversation by component.agentRun.conversation.subscribeAsState()
    val events by component.agentRun.events.subscribeAsState()
    val isRunning by component.agentRun.isRunning.collectAsState()
    val status by component.agentRun.status.subscribeAsState()
    val finalResponse by component.agentRun.finalResponse.subscribeAsState()

    LaunchedEffect(selectedSessionId) {
        component.agentRun.setSession(selectedSessionId)
    }

    val assistantDraft = remember(events, finalResponse, isRunning, selectedSessionId) {
        if (!isRunning || selectedSessionId.isBlank()) {
            null
        } else {
            val assistantContent = events
                .filter { it.type == StreamEventType.token }
                .map { it.content.orEmpty() }
                .joinToString("")
            val displayContent = if (finalResponse.isNotBlank()) finalResponse else assistantContent
            displayContent.takeIf { it.isNotBlank() }?.let {
                Message(
                    id = "assistant-draft",
                    sessionId = selectedSessionId,
                    role = MessageRole.ASSISTANT,
                    content = it,
                    timestamp = currentTimeMillis(),
                )
            }
        }
    }
    val visibleMessages = remember(conversation, assistantDraft) {
        if (assistantDraft == null) conversation else conversation + assistantDraft
    }

    // Derive tool timeline events from agent run events
    val toolEvents by remember(events) {
        derivedStateOf {
            events.filter { it.type == StreamEventType.tool_call || it.type == StreamEventType.tool_result }
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(GolemColors.bg),
    ) {
        val useOverlaySidebar = maxWidth < 720.dp
        val sessionSidebar: @Composable (Modifier) -> Unit = { sidebarModifier ->
            SessionSidebar(
                sessions = sessions,
                selectedSessionId = selectedSessionId,
                isLoading = isLoading,
                models = models,
                onSelectSession = {
                    component.sessionList.selectSession(it)
                    if (useOverlaySidebar) {
                        sidebarExpanded = false
                    }
                },
                onNewSession = {
                    component.sessionList.showCreateDialog()
                    if (useOverlaySidebar) {
                        sidebarExpanded = false
                    }
                },
                onDeleteSession = { component.sessionList.deleteSession(it) },
                onChangeModel = { sessionId, modelId ->
                    component.sessionList.changeSessionModel(sessionId, modelId)
                },
                modifier = sidebarModifier,
            )
        }

        if (useOverlaySidebar) {
            Box(modifier = Modifier.fillMaxSize()) {
                ChatContent(
                    selectedSessionId = selectedSessionId,
                    sessions = sessions,
                    models = models,
                    status = status,
                    visibleMessages = visibleMessages,
                    isRunning = isRunning,
                    toolEvents = toolEvents,
                    goal = goal,
                    onGoalChange = component.agentRun::updateGoal,
                    onSend = component.agentRun::startRun,
                    onToggleSidebar = { sidebarExpanded = !sidebarExpanded },
                    onModelSelected = { modelId ->
                        if (selectedSessionId.isNotBlank()) {
                            component.sessionList.changeSessionModel(selectedSessionId, modelId)
                        }
                    },
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
                            .clickable { sidebarExpanded = false },
                    )
                }

                AnimatedVisibility(
                    visible = sidebarExpanded,
                    enter = slideInHorizontally { -it } + fadeIn(),
                    exit = slideOutHorizontally { -it } + fadeOut(),
                    modifier = Modifier.fillMaxHeight(),
                ) {
                    sessionSidebar(
                        Modifier
                            .fillMaxHeight()
                            .widthIn(max = 320.dp)
                            .fillMaxWidth(0.88f),
                    )
                }
            }
        } else {
            Row(modifier = Modifier.fillMaxSize()) {
                AnimatedVisibility(
                    visible = sidebarExpanded,
                    enter = slideInHorizontally { -it } + fadeIn(),
                    exit = slideOutHorizontally { -it } + fadeOut(),
                ) {
                    sessionSidebar(
                        Modifier
                            .width(280.dp)
                            .fillMaxHeight(),
                    )
                }

                if (!sidebarExpanded) {
                    Box(
                        modifier = Modifier
                            .width(32.dp)
                            .fillMaxHeight()
                            .clickable { sidebarExpanded = true }
                            .semantics {
                                contentDescription = "Expand sidebar"
                                role = Role.Button
                            }
                            .padding(8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("☰", color = GolemColors.textMuted, fontSize = 16.sp)
                    }
                }

                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(GolemColors.bgElevated)
                )

                ChatContent(
                    selectedSessionId = selectedSessionId,
                    sessions = sessions,
                    models = models,
                    status = status,
                    visibleMessages = visibleMessages,
                    isRunning = isRunning,
                    toolEvents = toolEvents,
                    goal = goal,
                    onGoalChange = component.agentRun::updateGoal,
                    onSend = component.agentRun::startRun,
                    onToggleSidebar = { sidebarExpanded = !sidebarExpanded },
                    onModelSelected = { modelId ->
                        if (selectedSessionId.isNotBlank()) {
                            component.sessionList.changeSessionModel(selectedSessionId, modelId)
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
            }
        }
    }

    if (createDialogVisible) {
        ChatCreateSessionDialog(component = component.sessionList)
    }
}

@Composable
private fun ChatContent(
    selectedSessionId: String,
    sessions: List<dev.spola.app.models.ChatSession>,
    models: List<ModelInfo>,
    status: String,
    visibleMessages: List<Message>,
    isRunning: Boolean,
    toolEvents: List<StreamEvent>,
    goal: String,
    onGoalChange: (String) -> Unit,
    onSend: () -> Unit,
    onToggleSidebar: () -> Unit,
    onModelSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        ChatStatusBar(
            modelId = sessions.find { it.id == selectedSessionId }?.modelId ?: "",
            models = models,
            status = status,
            isConnected = true,
            onModelSelected = onModelSelected,
            onToggleSidebar = onToggleSidebar,
        )

        ChatMessagesArea(
            messages = visibleMessages,
            isStreaming = isRunning,
            toolEvents = toolEvents,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )

        ChatInputBar(
            value = goal,
            onValueChange = onGoalChange,
            onSend = onSend,
            isRunning = isRunning,
            isSessionSelected = selectedSessionId.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ── Session Sidebar ───────────────────────────────────────────────────

@Composable
private fun SessionSidebar(
    sessions: List<dev.spola.app.models.ChatSession>,
    selectedSessionId: String,
    isLoading: Boolean,
    models: List<ModelInfo>,
    onSelectSession: (String) -> Unit,
    onNewSession: () -> Unit,
    onDeleteSession: (String) -> Unit,
    onChangeModel: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = GolemColors.bgSurface,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "Sessions",
                    color = GolemColors.textPrimary,
                    fontSize = 14.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = GolemColors.textMuted,
                        )
                    }
                    TextButton(
                        onClick = onNewSession,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.semantics {
                            contentDescription = "New session"
                            role = Role.Button
                        },
                    ) {
                        Text("+ New", fontSize = 12.sp, color = GolemColors.accent)
                    }
                }
            }

            HorizontalDivider(color = GolemColors.bgElevated)

            // Session list
            if (isLoading) {
                LoadingSkeletonList(
                    count = 4,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            } else if (sessions.isEmpty()) {
                EmptyState(
                    emoji = "💬",
                    title = "No sessions yet",
                    message = "Create a new session to start chatting with the Golem agent.",
                    actionLabel = "New Session",
                    onAction = onNewSession,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(sessions, key = { it.id }) { session ->
                        AnimatedVisibility(
                            visible = true,
                            enter = slideInHorizontally { -it / 2 } + fadeIn(animationSpec = tween(300)),
                            label = "sessionItemAnim",
                        ) {
                            SessionSidebarItem(
                                session = session,
                                isSelected = session.id == selectedSessionId,
                                models = models,
                                onSelect = { onSelectSession(session.id) },
                                onDelete = { onDeleteSession(session.id) },
                                onChangeModel = { modelId -> onChangeModel(session.id, modelId) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatCreateSessionDialog(component: dev.spola.app.app.decompose.SessionListComponent) {
    val name by component.newSessionName.subscribeAsState()
    val modelId by component.newSessionModelId.subscribeAsState()
    val models by component.availableModels.subscribeAsState()
    val isLoading by component.isLoading.collectAsState()
    var showModelMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!isLoading) component.hideCreateDialog() },
        title = { Text("New Session") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = component::updateNewSessionName,
                    label = { Text("Session name") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            contentDescription = "New session name"
                            role = Role.Button
                        },
                )

                Column {
                    Text("Model:", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    val selectedModel = models.find { it.id == modelId }
                    Box {
                        OutlinedButton(
                            onClick = { showModelMenu = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics {
                                    contentDescription = "Select model for new session"
                                    role = Role.Button
                                },
                        ) {
                            Text(
                                selectedModel?.name ?: modelId.ifBlank { "Select model..." },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        DropdownMenu(
                            expanded = showModelMenu,
                            onDismissRequest = { showModelMenu = false },
                        ) {
                            if (models.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("No models found") },
                                    onClick = { showModelMenu = false },
                                )
                            } else {
                                models.forEach { model ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(model.name, style = MaterialTheme.typography.bodyMedium)
                                                Text(
                                                    "${model.provider} — ${model.description ?: ""}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        },
                                        onClick = {
                                            component.updateNewSessionModel(model.id)
                                            showModelMenu = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = component::createSession,
                enabled = name.isNotBlank() && !isLoading && models.isNotEmpty(),
                modifier = Modifier.semantics {
                    contentDescription = "Create new session"
                    role = Role.Button
                },
            ) {
                Text(if (isLoading) "Creating..." else "Create")
            }
        },
        dismissButton = {
            TextButton(
                onClick = component::hideCreateDialog,
                enabled = !isLoading,
                modifier = Modifier.semantics {
                    contentDescription = "Cancel creating session"
                    role = Role.Button
                },
            ) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun SessionSidebarItem(
    session: dev.spola.app.models.ChatSession,
    isSelected: Boolean,
    models: List<ModelInfo>,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onChangeModel: (String) -> Unit,
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val bgColor = if (isSelected) GolemColors.accent.copy(alpha = 0.12f) else GolemColors.bgSurface

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .semantics {
                contentDescription = "Session: ${session.title}, model: ${session.modelId.take(20)}"
                role = if (isSelected) Role.Tab else Role.Button
            }
            .padding(horizontal = 6.dp),
        color = bgColor,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = session.title,
                    color = if (isSelected) GolemColors.textPrimary else GolemColors.textSecondary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = onDelete,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    modifier = Modifier
                        .size(24.dp)
                        .semantics {
                            contentDescription = "Delete session: ${session.title}"
                            role = Role.Button
                        },
                ) {
                    Text("×", color = GolemColors.textMuted, fontSize = 14.sp)
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = session.modelId.take(20),
                    color = GolemColors.accent.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatShortTimestamp(session.createdAt),
                    color = GolemColors.textMuted,
                    fontSize = 10.sp,
                )
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Session") },
            text = { Text("Delete \"${session.title}\"?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    },
                    modifier = Modifier.semantics {
                        contentDescription = "Confirm delete session"
                        role = Role.Button
                    },
                ) {
                    Text("Delete", color = GolemColors.error)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteConfirm = false },
                    modifier = Modifier.semantics {
                        contentDescription = "Cancel delete session"
                        role = Role.Button
                    },
                ) {
                    Text("Cancel")
                }
            },
        )
    }
}

// ── Chat Status Bar ───────────────────────────────────────────────────

@Composable
private fun ChatStatusBar(
    modelId: String,
    models: List<ModelInfo>,
    status: String,
    isConnected: Boolean,
    onModelSelected: (String) -> Unit,
    onToggleSidebar: () -> Unit,
) {
    Surface(
        color = GolemColors.bgSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Sidebar toggle
            Text(
                text = "☰",
                color = GolemColors.textMuted,
                fontSize = 16.sp,
                modifier = Modifier
                    .clickable { onToggleSidebar() }
                    .semantics {
                        contentDescription = "Collapse sidebar"
                        role = Role.Button
                    },
            )

            // Model selector
            if (modelId.isNotBlank()) {
                ModelSelector(
                    models = models,
                    selectedModelId = modelId,
                    onModelSelected = onModelSelected,
                )
            } else {
                Text(
                    "No session selected",
                    color = GolemColors.textMuted,
                    fontSize = 12.sp,
                )
            }

            Spacer(Modifier.weight(1f))

            // Connection status dot
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (isConnected) GolemColors.success else GolemColors.error),
            )

            Text(
                text = if (isConnected) "Connected" else "Disconnected",
                color = if (isConnected) GolemColors.textSecondary else GolemColors.error,
                fontSize = 11.sp,
            )

            // Agent status
            Text(
                text = status.uppercase(),
                color = GolemColors.textMuted,
                fontSize = 11.sp,
            )
        }
    }

    HorizontalDivider(color = GolemColors.bgElevated)
}

// ── Chat Messages Area ───────────────────────────────────────────────

@Composable
private fun ChatMessagesArea(
    messages: List<Message>,
    isStreaming: Boolean,
    toolEvents: List<StreamEvent>,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // Auto-scroll to bottom on new messages
    LaunchedEffect(messages.size, isStreaming) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Box(
        modifier = modifier.background(GolemColors.bg),
    ) {
        if (messages.isEmpty()) {
            EmptyState(
                emoji = "💬",
                title = "Select or create a session",
                message = "Choose a session from the sidebar or create a new one to start chatting with the Golem agent.",
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(messages, key = { it.id }) { message ->
                    MessageBubble(message = message)
                }

                // Tool timeline while streaming
                if (isStreaming) {
                    item(key = "tool-timeline") {
                        ToolTimeline(toolEvents = toolEvents)
                    }
                }
            }
        }
    }
}

// ── Collapsible Tool Timeline ─────────────────────────────────────────

@Composable
private fun ToolTimeline(
    toolEvents: List<StreamEvent>,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
    ) {
        // "Thinking..." header — clickable to expand/collapse
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = 4.dp, bottomEnd = 12.dp))
                .background(GolemColors.thinkingBg)
                .clickable { expanded = !expanded }
                .semantics {
                    contentDescription = if (expanded) "Collapse tool call timeline" else "Expand tool call timeline"
                    role = Role.Button
                }
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Animated spinner
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(GolemColors.toolRunning),
            )

            Text(
                text = "Thinking...",
                color = GolemColors.textSecondary,
                fontSize = 12.sp,
            )

            Text(
                text = if (expanded) "▲" else "▼",
                color = GolemColors.textMuted,
                fontSize = 9.sp,
            )

            if (toolEvents.isNotEmpty()) {
                Text(
                    text = "${toolEvents.filter { it.type == StreamEventType.tool_result }.size} tools used",
                    color = GolemColors.textMuted,
                    fontSize = 10.sp,
                )
            }
        }

        // Collapsible tool call list
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
            label = "toolTimelineExpand",
        ) {
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
                    .background(GolemColors.toolCallBg)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (toolEvents.isEmpty()) {
                    Text(
                        text = "⏳ No tool calls yet…",
                        color = GolemColors.textMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                } else {
                    // Group consecutive tool_call + tool_result pairs
                    val grouped = mutableListOf<Pair<StreamEvent?, StreamEvent?>>()
                    var pendingCall: StreamEvent? = null
                    for (event in toolEvents) {
                        when (event.type) {
                            StreamEventType.tool_call -> {
                                pendingCall = event
                            }
                            StreamEventType.tool_result -> {
                                if (pendingCall != null) {
                                    grouped.add(pendingCall to event)
                                    pendingCall = null
                                } else {
                                    grouped.add(null to event)
                                }
                            }
                            else -> {}
                        }
                    }
                    if (pendingCall != null) {
                        grouped.add(pendingCall to null)
                    }

                    grouped.forEach { (call, result) ->
                        ToolTimelineItem(
                            toolName = call?.toolName ?: result?.toolName ?: "unknown",
                            isError = result?.content?.startsWith("Error") == true ||
                                result?.content?.startsWith("error") == true,
                            isPending = result == null,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolTimelineItem(
    toolName: String,
    isError: Boolean,
    isPending: Boolean,
) {
    val icon = when {
        isPending -> "🔄"
        isError -> "✗"
        else -> "✓"
    }
    val iconColor = when {
        isPending -> GolemColors.toolRunning
        isError -> GolemColors.toolError
        else -> GolemColors.toolSuccess
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.semantics {
            contentDescription = "Tool: $toolName, status: ${if (isPending) "running" else if (isError) "error" else "complete"}"
        },
    ) {
        Text(
            text = icon,
            fontSize = 12.sp,
        )
        Text(
            text = toolName,
            color = GolemColors.textSecondary,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = if (isPending) "running…" else if (isError) "error" else "done",
            color = iconColor,
            fontSize = 10.sp,
        )
    }
}

// ── Message Bubble ────────────────────────────────────────────────────

@Composable
private fun MessageBubble(message: Message) {
    val isUser = message.role == MessageRole.USER

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 720.dp),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        ) {
            // Role label
            Text(
                text = if (isUser) "You" else "Assistant",
                color = GolemColors.textMuted,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )

            // Bubble
            Box(
                modifier = Modifier
                    .clip(
                        RoundedCornerShape(
                            topStart = 16.dp,
                            topEnd = 16.dp,
                            bottomStart = if (isUser) 16.dp else 4.dp,
                            bottomEnd = if (isUser) 4.dp else 16.dp,
                        )
                    )
                    .background(
                        if (isUser) GolemColors.userBubble
                        else GolemColors.assistantBubble
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .semantics {
                        contentDescription = "${if (isUser) "Your" else "Assistant"} message: ${message.content.take(120)}"
                    },
            ) {
                // Check if content looks like code block
                val content = message.content
                if (content.startsWith("```") || content.startsWith("  ") || content.contains("\n  ")) {
                    Text(
                        text = content,
                        color = GolemColors.assistantBubbleText,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                } else {
                    Text(
                        text = content,
                        color = if (isUser) GolemColors.userBubbleText else GolemColors.assistantBubbleText,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                    )
                }
            }

            // Timestamp
            Text(
                text = formatShortTimestamp(message.timestamp),
                color = GolemColors.textMuted.copy(alpha = 0.6f),
                fontSize = 10.sp,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

// ── Chat Input Bar ────────────────────────────────────────────────────

@Composable
private fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    isRunning: Boolean,
    isSessionSelected: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = GolemColors.bgSurface,
    ) {
        Column {
            HorizontalDivider(color = GolemColors.bgElevated)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .weight(1f)
                        .onKeyEvent { keyEvent ->
                            if (keyEvent.key == Key.Enter && !isRunning && value.isNotBlank() && isSessionSelected) {
                                onSend()
                                true
                            } else false
                        }
                        .semantics {
                            contentDescription = if (isSessionSelected) "Chat input" else "Chat input — select a session first"
                            role = Role.Button
                        },
                    placeholder = {
                        Text(
                            if (isSessionSelected) "Type a message..." else "Select a session first",
                            color = GolemColors.textMuted,
                            fontSize = 13.sp,
                        )
                    },
                    textStyle = TextStyle(
                        color = GolemColors.textPrimary,
                        fontSize = 14.sp,
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = GolemColors.accent.copy(alpha = 0.5f),
                        unfocusedBorderColor = GolemColors.bgElevated,
                        cursorColor = GolemColors.accent,
                        focusedContainerColor = GolemColors.bg,
                        unfocusedContainerColor = GolemColors.bg,
                    ),
                    minLines = 1,
                    maxLines = 6,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Send,
                    ),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (!isRunning && value.isNotBlank() && isSessionSelected) {
                                onSend()
                            }
                        },
                    ),
                    enabled = !isRunning && isSessionSelected,
                )

                Button(
                    onClick = onSend,
                    enabled = value.isNotBlank() && !isRunning && isSessionSelected,
                    modifier = Modifier
                        .height(48.dp)
                        .semantics {
                            contentDescription = if (isSessionSelected) "Send message" else "Send message — select a session first"
                            role = Role.Button
                        },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GolemColors.accent,
                        disabledContainerColor = GolemColors.accent.copy(alpha = 0.3f),
                    ),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    if (isRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = GolemColors.textPrimary,
                        )
                    } else {
                        Text("Send", fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

// ── Utility ───────────────────────────────────────────────────────────

private fun formatShortTimestamp(millis: Long): String {
    val totalSeconds = millis / 1000
    val timeOfDay = totalSeconds % 86400
    val hours = timeOfDay / 3600
    val minutes = (timeOfDay % 3600) / 60
    return "${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}"
}
