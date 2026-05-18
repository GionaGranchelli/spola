package dev.spola.app.app.decompose

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.lifecycle.doOnDestroy
import dev.spola.app.app.randomUUID
import dev.spola.app.models.ChatSession
import dev.spola.app.models.ModelInfo
import dev.spola.app.models.SelectedSessionState
import dev.spola.app.network.GolemClient
import dev.spola.app.state.AppStateStore
import dev.spola.app.state.currentTimeMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val NO_GOLEM_HOST = "No configured Golem host"

class DefaultSessionListComponent(
    componentContext: ComponentContext,
    private val clientProvider: () -> GolemClient?,
    private val stateStore: AppStateStore?,
    private val reportFailure: (Throwable, String?) -> Unit,
) : SessionListComponent, ComponentContext by componentContext {
    // Use lifecycle-aware scope so coroutines are cancelled when component is destroyed
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _sessions = MutableValue<List<ChatSession>>(emptyList())
    override val sessions: Value<List<ChatSession>> = _sessions

    private val _availableModels = MutableValue<List<ModelInfo>>(emptyList())
    override val availableModels: Value<List<ModelInfo>> = _availableModels

    // Use empty string for "no session selected" since MutableValue requires non-null types
    private val _selectedSessionId = MutableValue("")
    override val selectedSessionId: Value<String> = _selectedSessionId

    private val _isLoading = MutableStateFlow(false)
    override val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _createDialogVisible = MutableStateFlow(false)
    override val createDialogVisible: StateFlow<Boolean> = _createDialogVisible.asStateFlow()

    private val _newSessionName = MutableValue("")
    override val newSessionName: Value<String> = _newSessionName

    private val _newSessionModelId = MutableValue("")
    override val newSessionModelId: Value<String> = _newSessionModelId

    private val _error = MutableStateFlow<String?>(null)
    override val error: StateFlow<String?> = _error.asStateFlow()

    init {
        lifecycle.doOnDestroy {
            scope.cancel()
        }
        // Restore previously selected session from local persistence
        val savedState = stateStore?.loadSelectedSessionState()
        _selectedSessionId.value = savedState?.sessionId ?: ""
        // Load sessions and models from backend
        refresh()
    }

    override fun refresh() {
        if (_isLoading.value) return
        scope.launch {
            _isLoading.value = true
            _error.value = null

            runCatching {
                val api = clientProvider() ?: error(NO_GOLEM_HOST)
                val sessions = api.getSessions()
                val models = api.getModels()
                sessions to models
            }.onSuccess { (sessions, models) ->
                _sessions.value = sessions
                _availableModels.value = models
                // If no session selected but sessions exist, don't auto-select
                // — user must explicitly click one
            }.onFailure {
                reportFailure(it, "Failed to load sessions")
                _error.value = it.message
            }

            _isLoading.value = false
        }
    }

    override fun selectSession(sessionId: String) {
        _selectedSessionId.value = sessionId
        // Persist selection locally
        stateStore?.saveSelectedSessionState(SelectedSessionState(sessionId = sessionId))
    }

    override fun showCreateDialog() {
        _newSessionName.value = ""
        // Default to first available model, or empty
        _newSessionModelId.value = _availableModels.value.firstOrNull()?.id ?: ""
        println(
            "[SessionList] showCreateDialog selectedModel=${_newSessionModelId.value.ifBlank { "<none>" }} " +
                "availableModels=${_availableModels.value.size}"
        )
        _createDialogVisible.value = true
    }

    override fun hideCreateDialog() {
        println("[SessionList] hideCreateDialog")
        _createDialogVisible.value = false
    }

    override fun updateNewSessionName(name: String) {
        _newSessionName.value = name
    }

    override fun updateNewSessionModel(modelId: String) {
        _newSessionModelId.value = modelId
    }

    override fun createSession() {
        val name = _newSessionName.value.trim()
        if (name.isBlank()) {
            println("[SessionList] createSession ignored because name is blank")
            return
        }
        if (_isLoading.value) {
            println("[SessionList] createSession ignored because loading is already in progress")
            return
        }

        scope.launch {
            _isLoading.value = true
            _error.value = null

            val modelId = _newSessionModelId.value.ifBlank {
                _availableModels.value.firstOrNull()?.id ?: "default"
            }

            // Derive providerId from the selected model, falling back to "ollama"
            val providerId = _availableModels.value.firstOrNull { it.id == modelId }?.provider ?: "ollama"

            val newSession = ChatSession(
                id = randomUUID(),
                title = name,
                createdAt = currentTimeMillis(),
                modelId = modelId,
                providerId = providerId,
            )

            println(
                "[SessionList] createSession request id=${newSession.id} title=${newSession.title} " +
                    "modelId=${newSession.modelId} providerId=${newSession.providerId}"
            )

            runCatching {
                val api = clientProvider() ?: error(NO_GOLEM_HOST)
                api.createSession(newSession)
            }.onSuccess { created ->
                println(
                    "[SessionList] createSession success id=${created.id} title=${created.title} " +
                        "modelId=${created.modelId} providerId=${created.providerId}"
                )
                val current = _sessions.value.toMutableList()
                current.add(0, created)
                _sessions.value = current
                _selectedSessionId.value = created.id
                stateStore?.saveSelectedSessionState(SelectedSessionState(sessionId = created.id))
                _createDialogVisible.value = false
            }.onFailure {
                println("[SessionList] createSession failure: ${it.message}")
                reportFailure(it, "Failed to create session")
                _error.value = it.message
            }

            _isLoading.value = false
        }
    }

    override fun deleteSession(sessionId: String) {
        scope.launch {
            _isLoading.value = true
            _error.value = null

            runCatching {
                val api = clientProvider() ?: error(NO_GOLEM_HOST)
                api.deleteSession(sessionId)
            }.onSuccess {
                _sessions.value = _sessions.value.filterNot { it.id == sessionId }
                if (_selectedSessionId.value == sessionId) {
                    _selectedSessionId.value = ""
                    stateStore?.saveSelectedSessionState(SelectedSessionState())
                }
            }.onFailure {
                reportFailure(it, "Failed to delete session")
                _error.value = it.message
            }

            _isLoading.value = false
        }
    }

    override fun changeSessionModel(sessionId: String, modelId: String) {
        scope.launch {
            _isLoading.value = true
            _error.value = null

            runCatching {
                val api = clientProvider() ?: error(NO_GOLEM_HOST)
                api.updateSessionModel(sessionId, modelId)
            }.onSuccess { updated ->
                // Update the session in our local list
                _sessions.value = _sessions.value.map {
                    if (it.id == sessionId) updated else it
                }
            }.onFailure {
                reportFailure(it, "Failed to update session model")
                _error.value = it.message
            }

            _isLoading.value = false
        }
    }
}
