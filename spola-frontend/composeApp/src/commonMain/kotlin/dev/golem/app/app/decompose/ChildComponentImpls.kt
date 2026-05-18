package dev.spola.app.app.decompose

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.lifecycle.doOnDestroy
import dev.spola.models.ScheduledJobResponse
import dev.spola.models.ToolInfo
import dev.spola.app.app.randomUUID
import dev.spola.app.models.Message
import dev.spola.app.models.MessageRole
import dev.spola.app.models.StreamEvent
import dev.spola.app.models.StreamEventType
import dev.spola.app.network.GolemClient
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

private const val NO_GOLEM_HOST = "No configured Golem host"

class DefaultAgentRunComponent(
    componentContext: ComponentContext,
    private val clientProvider: () -> GolemClient?,
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
        if (!_isRunning.value) {
            _status.value = "Ready"
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
        if (trimmedGoal.isBlank() || _isRunning.value || currentSessionId.isBlank()) return

        activeRun?.cancel()
        _submittedGoal.value = trimmedGoal
        _goal.value = ""
        _events.value = emptyList()
        _finalResponse.value = ""
        _status.value = "Starting"
        _isRunning.value = true
        appendConversationMessage(
            Message(
                id = randomUUID(),
                sessionId = currentSessionId,
                role = MessageRole.USER,
                content = trimmedGoal,
                timestamp = System.currentTimeMillis(),
            )
        )

        println("[AgentRun] startRun submittedGoal=${trimmedGoal.take(120)}")

        activeRun = scope.launch {
            runCatching {
                val api = clientProvider() ?: error(NO_GOLEM_HOST)
                api.streamAgentRun(trimmedGoal, _persona.value.trim().takeIf { it.isNotBlank() }).collect { event ->
                    appendEvent(event)
                    when (event.type) {
                        StreamEventType.status -> _status.value = event.content ?: "Running"
                        StreamEventType.complete -> {
                            _status.value = "Complete"
                            _finalResponse.value = event.content.orEmpty()
                            if (event.content.orEmpty().isNotBlank()) {
                                appendConversationMessage(
                                    Message(
                                        id = randomUUID(),
                                        sessionId = currentSessionId,
                                        role = MessageRole.ASSISTANT,
                                        content = event.content.orEmpty(),
                                        timestamp = System.currentTimeMillis(),
                                    )
                                )
                            }
                        }
                        StreamEventType.error -> _status.value = "Error"
                        else -> Unit
                    }
                }
            }.onFailure {
                _status.value = "Error"
                appendEvent(StreamEvent(StreamEventType.error, it.message ?: "Unknown error"))
                reportFailure(it, "Agent run failed")
            }
            _isRunning.value = false
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
    private val clientProvider: () -> GolemClient?,
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
                val api = clientProvider() ?: error(NO_GOLEM_HOST)
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
    private val clientProvider: () -> GolemClient?,
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
                val api = clientProvider() ?: error(NO_GOLEM_HOST)
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
                val api = clientProvider() ?: error(NO_GOLEM_HOST)
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
    private val clientProvider: () -> GolemClient?,
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
                val api = clientProvider() ?: error(NO_GOLEM_HOST)
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
