package dev.spola.app.app.pages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.collectAsState
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import dev.spola.app.app.decompose.DashboardComponent
import dev.spola.app.models.ChatSession
import dev.spola.app.models.Message
import dev.spola.app.models.MessageRole
import dev.spola.app.models.ModelInfo
import dev.spola.app.models.StreamEvent
import dev.spola.app.models.StreamEventType
import dev.spola.app.models.TokenUsageData
import dev.spola.app.state.currentTimeMillis

@Immutable
data class ChatPageState(
    val sessions: List<ChatSession>,
    val selectedSessionId: String,
    val models: List<ModelInfo>,
    val isLoading: Boolean,
    val createDialogVisible: Boolean,
    val goal: String,
    val conversation: List<Message>,
    val events: List<StreamEvent>,
    val isRunning: Boolean,
    val status: String,
    val finalResponse: String,
    val toolEvents: List<StreamEvent>,
    val visibleMessages: List<Message>,
    val turnTokens: String?,
    val cumulativeTokens: String?,
) {
    val selectedModelId: String
        get() = sessions.find { it.id == selectedSessionId }?.modelId.orEmpty()

    val isSessionSelected: Boolean
        get() = selectedSessionId.isNotBlank()
}

class ChatViewModel(
    val state: ChatPageState,
    private val component: DashboardComponent,
) {
    fun selectSession(sessionId: String) = component.sessionList.selectSession(sessionId)

    fun showCreateDialog() = component.sessionList.showCreateDialog()

    fun deleteSession(sessionId: String) = component.sessionList.deleteSession(sessionId)

    fun updateSessionModel(sessionId: String, modelId: String) {
        component.sessionList.changeSessionModel(sessionId, modelId)
    }

    fun updateSelectedSessionModel(modelId: String) {
        if (state.isSessionSelected) {
            component.sessionList.changeSessionModel(state.selectedSessionId, modelId)
        }
    }

    fun updateGoal(goal: String) = component.agentRun.updateGoal(goal)

    fun startRun() = component.agentRun.startRun()
}

@Composable
fun rememberChatViewModel(component: DashboardComponent): ChatViewModel {
    val sessions by component.sessionList.sessions.subscribeAsState()
    val selectedSessionId by component.sessionList.selectedSessionId.subscribeAsState()
    val models by component.sessionList.availableModels.subscribeAsState()
    val isLoading by component.sessionList.isLoading.collectAsState()
    val createDialogVisible by component.sessionList.createDialogVisible.collectAsState()
    val goal by component.agentRun.goal.subscribeAsState()
    val conversation by component.agentRun.conversation.subscribeAsState()
    val events by component.agentRun.events.subscribeAsState()
    val isRunning by component.agentRun.isRunning.collectAsState()
    val status by component.agentRun.status.subscribeAsState()
    val finalResponse by component.agentRun.finalResponse.subscribeAsState()
    val tokenUsage by component.agentRun.tokenUsage.collectAsState()

    LaunchedEffect(selectedSessionId) {
        component.agentRun.setSession(selectedSessionId)
    }

    val assistantDraft by remember(events, finalResponse, isRunning, selectedSessionId) {
        derivedStateOf {
            if (!isRunning || selectedSessionId.isBlank()) {
                null
            } else {
                val assistantContent = events
                    .filter { it.type == StreamEventType.token }
                    .joinToString("") { it.content.orEmpty() }
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
    }
    val visibleMessages by remember(conversation, assistantDraft) {
        derivedStateOf {
            (conversation + assistantDraft).filterNotNull()
        }
    }
    val toolEvents by remember(events) {
        derivedStateOf {
            events.filter { it.type == StreamEventType.tool_call || it.type == StreamEventType.tool_result }
        }
    }
    val turnTokens by remember(tokenUsage) {
        derivedStateOf {
            tokenUsage?.let { tu ->
                "Turn: ${formatNumber(tu.inputTokens)} in | ${formatNumber(tu.outputTokens)} out | ${formatNumber(tu.thinkingTokens)} think"
            }
        }
    }
    val cumulativeTokens by remember(tokenUsage) {
        derivedStateOf {
            tokenUsage?.let { tu ->
                "Total: ${formatNumber(tu.cumulativeInput)} in | ${formatNumber(tu.cumulativeOutput)} out | ${formatNumber(tu.cumulativeThinking)} think"
            }
        }
    }

    val state = remember(
        sessions,
        selectedSessionId,
        models,
        isLoading,
        createDialogVisible,
        goal,
        conversation,
        events,
        isRunning,
        status,
        finalResponse,
        toolEvents,
        visibleMessages,
        turnTokens,
        cumulativeTokens,
    ) {
        ChatPageState(
            sessions = sessions,
            selectedSessionId = selectedSessionId,
            models = models,
            isLoading = isLoading,
            createDialogVisible = createDialogVisible,
            goal = goal,
            conversation = conversation,
            events = events,
            isRunning = isRunning,
            status = status,
            finalResponse = finalResponse,
            toolEvents = toolEvents,
            visibleMessages = visibleMessages,
            turnTokens = turnTokens,
            cumulativeTokens = cumulativeTokens,
        )
    }

    return remember(state, component) {
        ChatViewModel(state = state, component = component)
    }
}

/**
 * Format a number with locale-aware comma grouping.
 * Works cross-platform (no java.text dependency).
 */
private fun formatNumber(value: Number): String {
    val num = value.toLong()
    if (num < 1000) return num.toString()
    val groups = buildList {
        var remaining = num
        while (remaining > 0) {
            add((remaining % 1000).toString().padStart(3, '0'))
            remaining /= 1000
        }
    }
    return groups.reversed().joinToString(",").trimStart('0').ifEmpty { "0" }
}
