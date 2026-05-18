package dev.spola.api

import dev.spola.GolemConfig
import dev.spola.workflow.WorkflowExecutionStore
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.apiWorkflowSessionRoutes(
    config: GolemConfig,
    executionStore: WorkflowExecutionStore,
) {
    get("/sessions/{id}/executions") {
        call.enforceBearerAuth(config.apiKey, insecure = config.insecure)
        val sessionId = call.parameters["id"] ?: throw IllegalArgumentException("missing session id")
        val executions = executionStore.listBySessionId(sessionId)
        call.respond(mapOf("executions" to executions))
    }
}
