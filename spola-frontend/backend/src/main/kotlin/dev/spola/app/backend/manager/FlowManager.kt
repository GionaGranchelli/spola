package dev.spola.app.backend.manager

import dev.spola.app.models.CommandStreamEvent
import dev.spola.app.models.StreamEvent
import dev.spola.app.models.SystemEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap

class FlowManager {
    private val sessionFlows = ConcurrentHashMap<String, MutableSharedFlow<StreamEvent>>()
    private val commandFlows = ConcurrentHashMap<String, MutableSharedFlow<CommandStreamEvent>>()
    private val _systemFlow = MutableSharedFlow<SystemEvent>(extraBufferCapacity = 10)
    val systemFlow: SharedFlow<SystemEvent> = _systemFlow.asSharedFlow()

    fun emitSystemEvent(event: SystemEvent) {
        _systemFlow.tryEmit(event)
    }

    fun getSessionFlow(sessionId: String): MutableSharedFlow<StreamEvent> {
        return sessionFlows.getOrPut(sessionId) { MutableSharedFlow(extraBufferCapacity = 100) }
    }

    fun getCommandFlow(approvalId: String): MutableSharedFlow<CommandStreamEvent> {
        return commandFlows.getOrPut(approvalId) { MutableSharedFlow(extraBufferCapacity = 100) }
    }

    fun removeSessionFlow(sessionId: String) {
        sessionFlows.remove(sessionId)
    }

    fun removeCommandFlow(approvalId: String) {
        commandFlows.remove(approvalId)
    }
}
