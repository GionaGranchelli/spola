package dev.spola.api

import dev.spola.SpolaConfig
import dev.spola.api.WorkflowRunRequest
import dev.spola.workflow.NewWorkflowExecution
import dev.spola.workflow.WorkflowExecutionInput
import dev.spola.workflow.WorkflowExecutionService
import dev.spola.workflow.WorkflowExecutionStore
import dev.spola.workflow.WorkflowTemplateRegistry
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

fun Route.apiWorkflowRoutes(
    config: SpolaConfig,
    workflowExecutionService: WorkflowExecutionService,
    workflowExecutionStore: WorkflowExecutionStore,
    workflowTemplateRegistry: WorkflowTemplateRegistry,
) {
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    // ── Workflow definition list ───────────────────────────────
    // Returns built-in templates as read-only "definitions" with
    // fields compatible with the OpenClaw frontend (id, name, description, enabled).
    get("/workflows") {
        call.enforceBearerAuth(config.security.apiKey)
        val templates = workflowTemplateRegistry.list().map { template ->
            mapOf<String, kotlinx.serialization.json.JsonElement>(
                "id" to kotlinx.serialization.json.JsonPrimitive(template.name),
                "name" to kotlinx.serialization.json.JsonPrimitive(template.name),
                "description" to kotlinx.serialization.json.JsonPrimitive("Built-in ${template.name} workflow (v${template.version})"),
                "enabled" to kotlinx.serialization.json.JsonPrimitive(true),
            )
        }
        call.respondText(
            contentType = ContentType.Application.Json,
            text = json.encodeToString(mapOf("workflows" to templates)),
        )
    }

    // ── Run a workflow execution ───────────────────────────────
    post("/workflows/run") {
        call.enforceBearerAuth(config.security.apiKey)
        val request = call.receive<WorkflowRunRequest>()
        workflowTemplateRegistry.resolve(request.workflowName)
        val execution = workflowExecutionService.enqueue(
            NewWorkflowExecution(
                definitionId = request.definitionId,
                workflowName = request.workflowName,
                sessionId = request.sessionId,
                triggerSource = "api",
                inputJson = json.encodeToString(
                    WorkflowExecutionInput(
                        goal = request.goal,
                        parametersJson = request.inputJson,
                    ),
                ),
            ),
        )
        call.respond(HttpStatusCode.Accepted, mapOf("executionId" to execution.id))
    }

    // ── List all executions ────────────────────────────────────
    get("/workflows/executions") {
        call.enforceBearerAuth(config.security.apiKey)
        val limit = call.parameters["limit"]?.toIntOrNull() ?: 50
        val executions = workflowExecutionStore.listAll(limit)
        call.respond(mapOf("executions" to executions))
    }

    // ── Get single execution ───────────────────────────────────
    get("/workflows/executions/{id}") {
        call.enforceBearerAuth(config.security.apiKey)
        val id = call.requirePathParameter("id", "execution id")
        val execution = workflowExecutionService.getExecution(id)
            ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Execution not found"))
        call.respond(execution)
    }

    // ── Approve a workflow execution ───────────────────────────
    post("/workflows/executions/{id}/approve") {
        call.enforceBearerAuth(config.security.apiKey)
        val id = call.requirePathParameter("id", "execution id")
        try {
            val approved = workflowExecutionService.approveExecution(id)
            if (approved) {
                call.respond(HttpStatusCode.Accepted, mapOf("status" to "approved"))
            } else {
                call.respond(
                    HttpStatusCode.Conflict,
                    mapOf("error" to "Execution is not in WAITING_APPROVAL state, not found, or has no checkpoint"),
                )
            }
        } catch (t: Throwable) {
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to (t.message ?: "Workflow resume failed")),
            )
        }
    }

    // ── List executions for a scheduler job ────────────────────
    get("/scheduler/jobs/{id}/executions") {
        call.enforceBearerAuth(config.security.apiKey)
        val id = call.requirePathParameter("id", "job id")
        val executions = workflowExecutionStore.listByTrigger("scheduler", id)
        call.respond(mapOf("executions" to executions))
    }

    // ── List executions for a kanban task ──────────────────────
    get("/kanban/tasks/{id}/executions") {
        call.enforceBearerAuth(config.security.apiKey)
        val id = call.requirePathParameter("id", "task id")
        val executions = workflowExecutionStore.listByTrigger("kanban", id)
        call.respond(mapOf("executions" to executions))
    }
}
