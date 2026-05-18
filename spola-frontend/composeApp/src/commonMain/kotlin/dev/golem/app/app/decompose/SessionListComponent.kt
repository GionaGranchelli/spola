package dev.spola.app.app.decompose

import com.arkivanov.decompose.value.Value
import dev.spola.app.models.ChatSession
import dev.spola.app.models.ModelInfo
import kotlinx.coroutines.flow.StateFlow

interface SessionListComponent {
    val sessions: Value<List<ChatSession>>
    val availableModels: Value<List<ModelInfo>>
    val selectedSessionId: Value<String>
    val isLoading: StateFlow<Boolean>
    val createDialogVisible: StateFlow<Boolean>
    val newSessionName: Value<String>
    val newSessionModelId: Value<String>
    val error: StateFlow<String?>

    /** Load sessions and models from backend */
    fun refresh()

    /** Select a session as active */
    fun selectSession(sessionId: String)

    /** Show/hide create session dialog */
    fun showCreateDialog()
    fun hideCreateDialog()

    /** Update new session name in dialog */
    fun updateNewSessionName(name: String)

    /** Update new session model in dialog */
    fun updateNewSessionModel(modelId: String)

    /** Create a new session with current name/model */
    fun createSession()

    /** Delete a session */
    fun deleteSession(sessionId: String)

    /** Change the model for an existing session */
    fun changeSessionModel(sessionId: String, modelId: String)
}
