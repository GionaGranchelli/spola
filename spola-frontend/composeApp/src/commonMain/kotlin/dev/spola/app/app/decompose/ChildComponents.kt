package dev.spola.app.app.decompose

import com.arkivanov.decompose.value.Value
import dev.spola.app.models.Message
import dev.spola.app.models.StreamEvent
import dev.spola.app.models.TokenUsageData
import kotlinx.coroutines.flow.StateFlow

interface AgentRunComponent {
    val goal: Value<String>
    val submittedGoal: Value<String>
    val conversation: Value<List<Message>>
    val persona: Value<String>
    val events: Value<List<StreamEvent>>
    val status: Value<String>
    val finalResponse: Value<String>
    val isRunning: StateFlow<Boolean>
    val tokenUsage: StateFlow<TokenUsageData?>

    fun setSession(sessionId: String)
    fun updateGoal(goal: String)
    fun updatePersona(persona: String)
    fun startRun()
    fun clearLog()
}

interface ToolBrowserComponent {
    val tools: Value<List<Map<String, String>>>
    val isLoading: StateFlow<Boolean>
    fun refresh()
}

interface MemorySearchComponent {
    val query: Value<String>
    val results: Value<List<Pair<String, String>>>
    val isLoading: StateFlow<Boolean>
    fun updateQuery(text: String)
    fun search()
    fun deleteEntry(key: String)
}

interface SchedulerListComponent {
    val jobs: Value<List<Map<String, String>>>
    val isLoading: StateFlow<Boolean>
    fun refresh()
}
