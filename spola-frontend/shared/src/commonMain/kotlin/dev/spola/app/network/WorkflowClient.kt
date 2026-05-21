package dev.spola.app.network

import dev.spola.app.models.WorkflowCreateRequest
import dev.spola.app.models.WorkflowDefinition
import dev.spola.app.models.WorkflowExecutionRecord
import dev.spola.app.models.WorkflowRunRequest
import dev.spola.app.models.WorkflowRunResponse
import dev.spola.app.models.WorkflowToggleRequest
import dev.spola.app.models.WorkflowUpdateRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class WorkflowClient(
    private val client: HttpClient,
    private val json: Json,
) {
    suspend fun getWorkflowDefinitions(): List<WorkflowDefinition> {
        val response = client.get("api/workflows").bodyAsText()
        val obj = json.parseToJsonElement(response).jsonObject
        val workflows = obj["workflows"] ?: return emptyList()
        return json.decodeFromJsonElement(ListSerializer(WorkflowDefinition.serializer()), workflows)
    }

    suspend fun createWorkflowDefinition(name: String, description: String): WorkflowDefinition =
        client.post("api/workflows") {
            contentType(ContentType.Application.Json)
            setBody(WorkflowCreateRequest(name, description))
        }.body()

    suspend fun updateWorkflowDefinition(
        id: String,
        name: String? = null,
        description: String? = null,
    ): WorkflowDefinition =
        client.put("api/workflows/$id") {
            contentType(ContentType.Application.Json)
            setBody(WorkflowUpdateRequest(name, description))
        }.body()

    suspend fun deleteWorkflowDefinition(id: String) {
        client.delete("api/workflows/$id")
    }

    suspend fun toggleWorkflowDefinition(id: String, enabled: Boolean) {
        client.post("api/workflows/$id/toggle") {
            contentType(ContentType.Application.Json)
            setBody(WorkflowToggleRequest(enabled))
        }
    }

    suspend fun runWorkflow(
        workflowName: String,
        goal: String,
        definitionId: String? = null,
        sessionId: String? = null,
        inputJson: String = "{}",
    ): WorkflowRunResponse =
        client.post("api/workflows/run") {
            contentType(ContentType.Application.Json)
            setBody(WorkflowRunRequest(workflowName, goal, definitionId, sessionId, inputJson))
        }.body()

    suspend fun getWorkflowExecutions(limit: Int = 50): List<WorkflowExecutionRecord> {
        val response = client.get("api/workflows/executions?limit=$limit").bodyAsText()
        val obj = json.parseToJsonElement(response).jsonObject
        val executions = obj["executions"] ?: return emptyList()
        return json.decodeFromJsonElement(ListSerializer(WorkflowExecutionRecord.serializer()), executions)
    }

    suspend fun getWorkflowExecution(id: String): WorkflowExecutionRecord =
        client.get("api/workflows/executions/$id").body()
}
