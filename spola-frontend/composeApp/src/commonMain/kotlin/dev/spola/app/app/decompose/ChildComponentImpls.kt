package dev.spola.app.app.decompose

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.lifecycle.doOnDestroy
import dev.spola.models.ScheduledJobResponse
import dev.spola.models.ToolInfo
import dev.spola.app.app.randomUUID
import dev.spola.app.models.BackendMessage
import dev.spola.app.models.Message
import dev.spola.app.models.MessageRole
import dev.spola.app.models.StreamEvent
import dev.spola.app.models.StreamEventType
import dev.spola.app.models.TokenUsageData
import dev.spola.app.models.ChatSession
import dev.spola.app.models.toMessage
import dev.spola.app.network.SpolaClient
import dev.spola.app.state.currentTimeMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

private const val NO_SPOLA_HOST = "No configured Spola backend host"

class DefaultAgentRunComponent(
    componentContext: ComponentContext,
    private val clientProvider: () -> SpolaClient?,
    private val reportFailure: (Throwable, String?) -> Unit,
) : AgentRunComponent, ComponentContext by componentContext {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _goal = MutableValue("")
    override val goal: Value<String> = _goal
    private val _submittedGoal = MutableValue("")
    override val submittedGoal: Value<String> = _submittedGoal
    private val _conversation = MutableValue<List<Message>>(emptyList())
    override val conversation: Value<List<Message>> = _conversation
    private val _persona = MutableValue("")
    override val persona: Value<String> = _persona
    private val _events = MutableValue<List<StreamEvent>>(emptyList())
    override val events: Value<List<StreamEvent>> = _events
    private val _status = MutableValue("Ready")
    override val status: Value<String> = _status
    private val _finalResponse = MutableValue("")
    override val finalResponse: Value<String> = _finalResponse
    private val _isRunning = MutableStateFlow(false)
    override val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
    private var activeRun: Job? = null
    private var currentSessionId: String = ""
    private val conversationBySessionId = mutableMapOf<String, List<Message>>()
    private var receivedComplete = false
    private val _tokenUsage = MutableStateFlow<TokenUsageData?>(null)
    override val tokenUsage: StateFlow<TokenUsageData?> = _tokenUsage.asStateFlow()

    init {
        lifecycle.doOnDestroy {
            scope.cancel()
        }
    }

    override fun setSession(sessionId: String) {
        currentSessionId = sessionId
        _conversation.value = if (sessionId.isBlank()) emptyList() else conversationBySessionId[sessionId].orEmpty()
        _events.value = emptyList()
        _finalResponse.value = ""
        _submittedGoal.value = ""
        _tokenUsage.value = null
        if (!_isRunning.value) {
            _status.value = "Ready"
        }
        // Load session messages from the backend for persistence.
        // This ensures conversations survive app restarts.
        if (sessionId.isNotBlank()) {
            scope.launch {
                val api = clientProvider() ?: return@launch
                runCatching {
                    val backendMessages = api.getBackendMessages(sessionId)
                    val messages = backendMessages.mapIndexed { index, backendMsg ->
                        backendMsg.toMessage(
                            sessionId = sessionId,
                            id = "${sessionId}-msg-$index",
                            timestamp = currentTimeMillis() - (backendMessages.size - index) * 1000L,
                        )
                    }
                    conversationBySessionId[sessionId] = messages
                    if (sessionId == currentSessionId) {
                        _conversation.value = messages
                    }
                }.onFailure {
                    println("[AgentRun] Failed to load messages for session $sessionId: ${it.message}")
                    // Fall back to whatever we have in memory
                }
            }
        }
    }

    override fun updateGoal(goal: String) {
        _goal.value = goal
    }

    override fun updatePersona(persona: String) {
        _persona.value = persona
    }

    override fun startRun() {
        val trimmedGoal = _goal.value.trim()
        if (trimmedGoal.isBlank() || _isRunning.value) return

        // !-prefixed commands route to shell execution instead of the agent loop
        if (trimmedGoal.startsWith("!")) {
            val command = trimmedGoal.removePrefix("!").trim()
            if (command.isNotBlank()) {
                // Need a session first — auto-create if blank
                if (currentSessionId.isBlank()) {
                    scope.launch {
                        val api = clientProvider() ?: return@launch
                        runCatching {
                            val newSession = api.createSession(
                                ChatSession(
                                    id = randomUUID(),
                                    title = command.take(80),
                                    createdAt = currentTimeMillis(),
                                    modelId = "",
                                    providerId = "ollama",
                                )
                            )
                            currentSessionId = newSession.id
                            conversationBySessionId[newSession.id] = emptyList()
                            launchExecCommand(command)
                        }.onFailure {
                            reportFailure(it, "Failed to create session")
                        }
                    }
                    return
                }
                launchExecCommand(command)
            }
            return
        }

        // If no session is selected, create one automatically from the goal text
        if (currentSessionId.isBlank()) {
            scope.launch {
                val api = clientProvider() ?: return@launch
                val title = trimmedGoal.take(80)
                runCatching {
                    val newSession = api.createSession(
                        ChatSession(
                            id = randomUUID(),
                            title = title,
                            createdAt = currentTimeMillis(),
                            modelId = "",
                            providerId = "ollama",
                        )
                    )
                    currentSessionId = newSession.id
                    conversationBySessionId[newSession.id] = emptyList()
                    println("[AgentRun] Auto-created session ${newSession.id} title=${newSession.title}")
                    // Now start the run with this session
                    launchRun(trimmedGoal)
                }.onFailure {
                    println("[AgentRun] Failed to auto-create session: ${it.message}")
                    reportFailure(it, "Failed to create session")
                }
            }
            return
        }

        launchRun(trimmedGoal)
    }

    private fun launchRun(goal: String) {
        activeRun?.cancel()
        _submittedGoal.value = goal
        _goal.value = ""
        _events.value = emptyList()
        _finalResponse.value = ""
        _status.value = "Starting"
        _tokenUsage.value = null
        _isRunning.value = true
        receivedComplete = false
        println("[AgentRun] launchRun goal=${goal.take(120)} session=$currentSessionId")
        appendConversationMessage(
            Message(
                id = randomUUID(),
                sessionId = currentSessionId,
                role = MessageRole.USER,
                content = goal,
                timestamp = currentTimeMillis(),
            )
        )

        println("[AgentRun] launchRun submittedGoal=${goal.take(120)}")

        activeRun = scope.launch {
            runCatching {
                val api = clientProvider() ?: error(NO_SPOLA_HOST)
                api.streamSessionAgentRun(currentSessionId, goal, _persona.value.trim().takeIf { it.isNotBlank() })
                    .collect { event ->
                        appendEvent(event)
                        when (event.type) {
                            StreamEventType.status -> {
                                _status.value = event.content ?: "Running"
                                println("[AgentRun] status=${_status.value}")
                            }
                            StreamEventType.complete -> {
                                receivedComplete = true
                                _status.value = "Complete"
                                _finalResponse.value = event.content.orEmpty()
                                println("[AgentRun] COMPLETE received — finalResponse len=${event.content?.length ?: 0}")
                                if (event.content.orEmpty().isNotBlank()) {
                                    appendConversationMessage(
                                        Message(
                                            id = randomUUID(),
                                            sessionId = currentSessionId,
                                            role = MessageRole.ASSISTANT,
                                            content = event.content.orEmpty(),
                                            timestamp = currentTimeMillis(),
                                        )
                                    )
                                    println("[AgentRun] assistant message appended to conversation")
                                }
                            }
                            StreamEventType.token_usage -> {
                                _tokenUsage.value = TokenUsageData(
                                    inputTokens = event.inputTokens ?: 0,
                                    outputTokens = event.outputTokens ?: 0,
                                    thinkingTokens = event.thinkingTokens ?: 0,
                                    cumulativeInput = event.cumulativeInput ?: 0,
                                    cumulativeOutput = event.cumulativeOutput ?: 0,
                                    cumulativeThinking = event.cumulativeThinking ?: 0,
                                )
                                println("[AgentRun] token_usage: in=${event.inputTokens} out=${event.outputTokens} think=${event.thinkingTokens}")
                            }
                            StreamEventType.error -> {
                                _status.value = "Error"
                                println("[AgentRun] ERROR event: ${event.content}")
                            }
                            else -> Unit
                        }
                    }
            }.onFailure {
                println("[AgentRun] FAILURE: ${it.message} | receivedComplete=$receivedComplete")
                // CRITICAL: Do NOT overwrite "Complete" status if we already got the complete event.
                // The SSE connection closing after "complete" is normal — not an error.
                if (!receivedComplete) {
                    _status.value = "Error"
                    appendEvent(StreamEvent(StreamEventType.error, it.message ?: "Unknown error"))
                    reportFailure(it, "Agent run failed")
                } else {
                    _status.value = "Complete"
                    println("[AgentRun] Suppressed onFailure — complete was already received. This is normal.")
                }
            }
            _isRunning.value = false
            println("[AgentRun] run ended — status=${_status.value} isRunning=false")
            if (_status.value != "Error" && _status.value != "Complete") {
                _status.value = "Ready"
            }
        }
    }

    private fun launchExecCommand(command: String) {
        activeRun?.cancel()
        _submittedGoal.value = "! $command"
        _goal.value = ""
        _events.value = emptyList()
        _finalResponse.value = ""
        _status.value = "Executing"
        _isRunning.value = true
        receivedComplete = false
        println("[AgentRun] launchExecCommand cmd=$command session=$currentSessionId")
        appendConversationMessage(
            Message(
                id = randomUUID(),
                sessionId = currentSessionId,
                role = MessageRole.USER,
                content = "! $command",
                timestamp = currentTimeMillis(),
            )
        )

        activeRun = scope.launch {
            runCatching {
                val api = clientProvider() ?: error(NO_SPOLA_HOST)
                val execOutput = StringBuilder()
                api.streamSessionExec(currentSessionId, command)
                    .collect { event ->
                        appendEvent(event)
                        when (event.type) {
                            StreamEventType.token -> {
                                execOutput.append(event.content.orEmpty())
                            }
                            StreamEventType.complete -> {
                                receivedComplete = true
                                _status.value = "Complete"
                                val output = execOutput.toString()
                                _finalResponse.value = output
                                println("[AgentRun] EXEC COMPLETE — output len=${output.length}")
                                if (output.isNotBlank()) {
                                    appendConversationMessage(
                                        Message(
                                            id = randomUUID(),
                                            sessionId = currentSessionId,
                                            role = MessageRole.ASSISTANT,
                                            content = output,
                                            timestamp = currentTimeMillis(),
                                        )
                                    )
                                }
                            }
                            StreamEventType.error -> {
                                _status.value = "Error"
                                println("[AgentRun] EXEC ERROR: ${event.content}")
                                if (event.content.orEmpty().isNotBlank()) {
                                    appendConversationMessage(
                                        Message(
                                            id = randomUUID(),
                                            sessionId = currentSessionId,
                                            role = MessageRole.ASSISTANT,
                                            content = "❌ ${event.content.orEmpty()}",
                                            timestamp = currentTimeMillis(),
                                        )
                                    )
                                }
                            }
                            else -> Unit
                        }
                    }
            }.onFailure {
                println("[AgentRun] EXEC FAILURE: ${it.message} | receivedComplete=$receivedComplete")
                if (!receivedComplete) {
                    _status.value = "Error"
                    appendEvent(StreamEvent(StreamEventType.error, it.message ?: "Unknown error"))
                    reportFailure(it, "Shell exec failed")
                } else {
                    _status.value = "Complete"
                    println("[AgentRun] Suppressed exec onFailure — complete was already received.")
                }
            }
            _isRunning.value = false
            println("[AgentRun] exec run ended — status=${_status.value} isRunning=false")
            if (_status.value != "Error" && _status.value != "Complete") {
                _status.value = "Ready"
            }
        }
    }

    override fun clearLog() {
        activeRun?.cancel()
        _submittedGoal.value = ""
        _events.value = emptyList()
        _finalResponse.value = ""
        _status.value = "Ready"
        _tokenUsage.value = null
        _isRunning.value = false
    }

    private fun appendEvent(event: StreamEvent) {
        val current = _events.value.toMutableList()
        if (event.type == StreamEventType.token && current.lastOrNull()?.type == StreamEventType.token) {
            val last = current.removeLast()
            current += last.copy(content = last.content.orEmpty() + event.content.orEmpty())
        } else {
            current += event
        }
        _events.value = current
    }

    private fun appendConversationMessage(message: Message) {
        if (message.sessionId.isBlank()) return
        val updated = conversationBySessionId[message.sessionId].orEmpty() + message
        conversationBySessionId[message.sessionId] = updated
        if (message.sessionId == currentSessionId) {
            _conversation.value = updated
        }
    }
}

class DefaultToolBrowserComponent(
    componentContext: ComponentContext,
    private val clientProvider: () -> SpolaClient?,
    private val reportFailure: (Throwable, String?) -> Unit,
) : ToolBrowserComponent, ComponentContext by componentContext {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _tools = MutableValue<List<Map<String, String>>>(emptyList())
    override val tools: Value<List<Map<String, String>>> = _tools
    private val _isLoading = MutableStateFlow(false)
    override val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        lifecycle.doOnDestroy {
            scope.cancel()
        }
        refresh()
    }

    override fun refresh() {
        if (_isLoading.value) return

        scope.launch {
            _isLoading.value = true
            runCatching {
                val api = clientProvider() ?: error(NO_SPOLA_HOST)
                api.getTools().map(ToolInfo::toUiMap)
            }.onSuccess {
                _tools.value = it
            }.onFailure {
                reportFailure(it, "Failed to load tools")
            }
            _isLoading.value = false
        }
    }
}

class DefaultMemorySearchComponent(
    componentContext: ComponentContext,
    private val clientProvider: () -> SpolaClient?,
    private val reportFailure: (Throwable, String?) -> Unit,
) : MemorySearchComponent, ComponentContext by componentContext {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _query = MutableValue("")
    override val query: Value<String> = _query
    private val _results = MutableValue<List<Pair<String, String>>>(emptyList())
    override val results: Value<List<Pair<String, String>>> = _results
    private val _isLoading = MutableStateFlow(false)
    override val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        lifecycle.doOnDestroy {
            scope.cancel()
        }
    }

    override fun updateQuery(text: String) {
        _query.value = text
    }

    override fun search() {
        val trimmedQuery = _query.value.trim()
        if (trimmedQuery.isBlank() || _isLoading.value) return

        scope.launch {
            _isLoading.value = true
            runCatching {
                val api = clientProvider() ?: error(NO_SPOLA_HOST)
                api.searchMemory(trimmedQuery)
            }.onSuccess {
                _results.value = it
            }.onFailure {
                reportFailure(it, "Memory search failed")
            }
            _isLoading.value = false
        }
    }

    override fun deleteEntry(key: String) {
        if (key.isBlank() || _isLoading.value) return

        scope.launch {
            _isLoading.value = true
            runCatching {
                val api = clientProvider() ?: error(NO_SPOLA_HOST)
                api.deleteMemory(key)
            }.onSuccess {
                _results.value = _results.value.filterNot { it.first == key }
            }.onFailure {
                reportFailure(it, "Failed to delete memory entry")
            }
            _isLoading.value = false
        }
    }
}

class DefaultSchedulerListComponent(
    componentContext: ComponentContext,
    private val clientProvider: () -> SpolaClient?,
    private val reportFailure: (Throwable, String?) -> Unit,
) : SchedulerListComponent, ComponentContext by componentContext {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _jobs = MutableValue<List<Map<String, String>>>(emptyList())
    override val jobs: Value<List<Map<String, String>>> = _jobs
    private val _isLoading = MutableStateFlow(false)
    override val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        lifecycle.doOnDestroy {
            scope.cancel()
        }
        refresh()
    }

    override fun refresh() {
        if (_isLoading.value) return

        scope.launch {
            _isLoading.value = true
            runCatching {
                val api = clientProvider() ?: error(NO_SPOLA_HOST)
                api.getScheduledJobs().map(ScheduledJobResponse::toUiMap)
            }.onSuccess {
                _jobs.value = it
            }.onFailure {
                reportFailure(it, "Failed to load scheduler jobs")
            }
            _isLoading.value = false
        }
    }
}

private fun ToolInfo.toUiMap(): Map<String, String> = mapOf(
    "name" to name,
    "description" to description,
    "parameters" to parameters.entries.joinToString("\n") { (key, value) -> "$key: $value" }.ifBlank { "No parameters" },
)

private fun ScheduledJobResponse.toUiMap(): Map<String, String> = mapOf(
    "id" to id,
    "name" to name,
    "goal" to goal,
    "schedule" to cronExpression,
    "enabled" to enabled.toString(),
)
