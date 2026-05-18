package dev.spola.app.network

import dev.spola.models.AgentRunRequest
import dev.spola.models.AgentRunResponse
import dev.spola.models.ScheduledJobResponse
import dev.spola.models.ToolInfo
import dev.spola.app.models.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.forms.*
import io.ktor.client.plugins.sse.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

class GolemClient(
    private val httpClient: HttpClient,
    private val baseUrl: String = "http://localhost:8082",
    private val authToken: String? = null
) {
    // Normalize: strip trailing slash so all paths can use leading-slash consistently
    private val normalizedBaseUrl = baseUrl.trimEnd('/')
    private val json = Json {
        prettyPrint = true
        isLenient = true
        ignoreUnknownKeys = true
    }

    private val client = httpClient.config {
        expectSuccess = true
        install(SSE)
        install(HttpTimeout) {
            requestTimeoutMillis = 600000 // 10 minutes
            connectTimeoutMillis = 20000  // 20 seconds
            socketTimeoutMillis = 600000  // 10 minutes
        }
        install(ContentNegotiation) {
            json(json)
        }
        HttpResponseValidator {
            handleResponseExceptionWithRequest { cause, request ->
                when (cause) {
                    is ClientRequestException, is ServerResponseException -> {
                        val response = when (cause) {
                            is ClientRequestException -> cause.response
                            is ServerResponseException -> cause.response
                            else -> null
                        } ?: throw cause
                        val responseText = runCatching { response.bodyAsText() }.getOrDefault("")
                        val detail = responseText.takeIf { it.isNotBlank() } ?: response.status.description
                        throw IllegalStateException(
                            "HTTP ${response.status.value} at ${request.url.encodedPath}: $detail",
                            cause
                        )
                    }
                }
            }
        }
        defaultRequest {
            url(normalizedBaseUrl)
            contentType(ContentType.Application.Json)
            authToken?.let {
                header(HttpHeaders.Authorization, "Bearer $it")
            }
        }
    }

    fun streamEvents(sessionId: String): Flow<StreamEvent> =
        streamWithRetry("api/session/$sessionId/stream") { _, data -> json.decodeFromString<StreamEvent>(data) }

    fun streamSystemEvents(): Flow<SystemEvent> =
        streamWithRetry("api/system/stream") { _, data -> json.decodeFromString<SystemEvent>(data) }

    fun streamAgentRun(goal: String, persona: String? = null): Flow<StreamEvent> = flow {
        client.sse(
            urlString = "api/agent/run/stream",
            request = {
                method = HttpMethod.Post
                contentType(ContentType.Application.Json)
                setBody(AgentRunRequest(goal = goal, persona = persona))
                timeout {
                    requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                    socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                }
            }
        ) {
            incoming.collect { event ->
                val data = event.data ?: return@collect
                val type = event.event ?: return@collect
                emit(parseAgentRunEvent(type, data))
            }
        }
    }

    private fun <T> streamWithRetry(
        urlString: String,
        mapper: (String?, String) -> T
    ): Flow<T> = flow {
        var shouldRetry = true
        while (shouldRetry) {
            try {
                client.sse(
                    urlString = urlString,
                    request = {
                        timeout {
                            requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                            socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                        }
                    }
                ) {
                    incoming.collect { event ->
                        val data = event.data
                        if (data != null) {
                            emit(mapper(event.event, data))
                        }
                    }
                }
                shouldRetry = false
            } catch (e: Throwable) {
                if (isRecoverableSseDisconnect(e)) {
                    kotlinx.coroutines.delay(1000)
                } else {
                    throw e
                }
            }
        }
    }

    suspend fun getSessions(): List<ChatSession> = client.get("api/sessions").body()

    suspend fun getSession(sessionId: String): ChatSession = client.get("api/session/$sessionId").body()

    suspend fun getMessages(sessionId: String): List<Message> = client.get("api/session/$sessionId/messages").body()

    suspend fun createSession(session: ChatSession): ChatSession =
        client.post("api/session") {
            contentType(ContentType.Application.Json)
            setBody(session)
        }.body()

    suspend fun deleteSession(id: String) {
        client.delete("api/session/$id")
    }

    suspend fun sendMessage(sessionId: String, content: String): Message =
        client.post("api/session/$sessionId/message") {
            contentType(ContentType.Text.Plain)
            setBody(content)
        }.body()

    suspend fun uploadSessionFile(sessionId: String, fileName: String, fileBytes: ByteArray): String =
        client.submitFormWithBinaryData(
            url = "api/session/$sessionId/upload",
            formData = formData {
                append(
                    key = "file",
                    value = fileBytes,
                    headers = Headers.build {
                        append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                        append(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
                    },
                )
            },
        ).body<FileMetadata>().id

    suspend fun sendSessionMessage(sessionId: String, text: String, fileRef: String? = null) {
        val payload = if (fileRef.isNullOrBlank()) {
            text
        } else {
            buildString {
                append(text)
                if (text.isNotBlank()) {
                    append('\n')
                }
                append("[file:")
                append(fileRef)
                append(']')
            }
        }
        client.post("api/session/$sessionId/message") {
            contentType(ContentType.Text.Plain)
            setBody(payload)
        }
    }

    suspend fun getModels(): List<ModelInfo> =
        client.get("api/models").body()

    suspend fun updateSessionModel(sessionId: String, modelId: String): ChatSession =
        client.post("api/session/$sessionId/model") {
            contentType(ContentType.Application.Json)
            setBody(SessionModelUpdateRequest(modelId))
        }.body()

    suspend fun getAgentStatus(): Map<String, String> {
        val response = client.get("api/agent/status").bodyAsText()
        val obj = json.parseToJsonElement(response).jsonObject
        return obj.mapValues { it.value.jsonPrimitive.contentOrNull ?: it.value.toString() }
    }

    suspend fun runAgent(goal: String, model: String?): AgentRunResponse =
        client.post("api/agent/run") {
            contentType(ContentType.Application.Json)
            setBody(AgentRunRequest(goal, model))
        }.body()

    suspend fun getTools(): List<ToolInfo> =
        client.get("api/tools").body<ToolsResponse>().tools.map { tool ->
            ToolInfo(
                name = tool.name,
                description = tool.description,
                parameters = tool.parameters.properties.mapValues { (_, parameter) -> parameter.description },
            )
        }

    suspend fun searchMemory(query: String): List<Pair<String, String>> =
        client.get("api/memory") {
            parameter("q", query)
        }.body<MemoryEntriesResponse>().entries.map { it.key to it.value }

    suspend fun deleteMemory(key: String) {
        client.delete("api/memory/$key")
    }

    /**
     * Fetches pairing info from a Golem server at the given URL (no auth required).
     */
    suspend fun fetchPairingInfo(serverUrl: String): PairingInfoResponse {
        val url = serverUrl.trimEnd('/') + "/api/pairing/info"
        return client.get(url).body()
    }

    suspend fun getScheduledJobs(): List<ScheduledJobResponse> {
        val endpointCandidates = listOf(
            "api/scheduler",
            "api/scheduler/jobs",
            "api/schedules",
        )

        endpointCandidates.forEach { path ->
            val jobs = runCatching {
                val payload = client.get(path).bodyAsText()
                parseScheduledJobs(payload)
            }.getOrNull()

            if (jobs != null) {
                return jobs
            }
        }

        val fallback = runAgent(
            goal = """
                Use the scheduler_list tool and return only JSON in the form
                {"jobs":[{"id":"","name":"","goal":"","cronExpression":"","enabled":true,"createdAt":0,"nextRunAt":0}]}
            """.trimIndent(),
            model = null,
        )
        return parseScheduledJobs(fallback.result)
    }

    suspend fun getKanbanCards(): List<KanbanCard> =
        client.get("api/kanban").body()

    suspend fun createKanbanCard(text: String): KanbanCard =
        client.post("api/kanban") {
            contentType(ContentType.Application.Json)
            setBody(KanbanCardCreateRequest(text))
        }.body()

    suspend fun updateKanbanCard(id: String, text: String, status: String): KanbanCard =
        client.put("api/kanban/$id") {
            contentType(ContentType.Application.Json)
            setBody(KanbanCardUpdateRequest(text = text, status = status))
        }.body()

    suspend fun deleteKanbanCard(id: String) {
        client.delete("api/kanban/$id")
    }

    suspend fun getWorkflowDefinitions(): List<WorkflowDefinition> {
        val response = client.get("api/workflows").bodyAsText()
        val obj = json.parseToJsonElement(response).jsonObject
        val workflows = obj["workflows"] ?: return emptyList()
        return json.decodeFromJsonElement(
            kotlinx.serialization.builtins.ListSerializer(WorkflowDefinition.serializer()),
            workflows,
        )
    }

    suspend fun createWorkflowDefinition(name: String, description: String): WorkflowDefinition =
        client.post("api/workflows") {
            contentType(ContentType.Application.Json)
            setBody(WorkflowCreateRequest(name, description))
        }.body()

    suspend fun updateWorkflowDefinition(id: String, name: String? = null, description: String? = null): WorkflowDefinition =
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
        return json.decodeFromJsonElement(
            kotlinx.serialization.builtins.ListSerializer(WorkflowExecutionRecord.serializer()),
            executions,
        )
    }

    suspend fun getWorkflowExecution(id: String): WorkflowExecutionRecord =
        client.get("api/workflows/executions/$id").body()

    fun close() {
        client.close()
    }

    private fun isRecoverableSseDisconnect(error: Throwable): Boolean {
        var cause: Throwable? = error
        while (cause != null) {
            val message = cause.message?.lowercase().orEmpty()
            if (
                "unexpected end of stream" in message ||
                "end of stream" in message ||
                "stream was reset" in message ||
                "canceled" in message ||
                "cancelled" in message ||
                "socket closed" in message ||
                "connection reset" in message
            ) {
                return true
            }
            cause = cause.cause
        }
        return false
    }

    private fun parseAgentRunEvent(type: String, data: String): StreamEvent {
        val payload = json.parseToJsonElement(data).jsonObject
        return when (type) {
            "status" -> {
                val status = payload["status"]?.jsonPrimitive?.contentOrNull
                val message = payload["message"]?.jsonPrimitive?.contentOrNull
                StreamEvent(
                    type = StreamEventType.status,
                    content = listOfNotNull(status, message).joinToString(": ").ifBlank { null },
                )
            }

            "tool_call" -> StreamEvent(
                type = StreamEventType.tool_call,
                content = "Calling ${payload["name"]?.jsonPrimitive?.contentOrNull.orEmpty()}",
                toolName = payload["name"]?.jsonPrimitive?.contentOrNull,
                toolArgs = payload["arguments"]?.toString(),
            )

            "tool_result" -> StreamEvent(
                type = StreamEventType.tool_result,
                content = payload["error"]?.jsonPrimitive?.contentOrNull
                    ?: payload["output"]?.jsonPrimitive?.contentOrNull,
                toolName = payload["name"]?.jsonPrimitive?.contentOrNull,
            )

            "token" -> StreamEvent(
                type = StreamEventType.token,
                content = payload["text"]?.jsonPrimitive?.contentOrNull,
            )

            "error" -> StreamEvent(
                type = StreamEventType.error,
                content = payload["error"]?.jsonPrimitive?.contentOrNull,
            )

            "complete" -> StreamEvent(
                type = StreamEventType.complete,
                content = payload["result"]?.jsonPrimitive?.contentOrNull,
            )

            else -> StreamEvent(
                type = StreamEventType.error,
                content = "Unsupported event type: $type",
            )
        }
    }

    private fun parseScheduledJobs(rawPayload: String): List<ScheduledJobResponse> {
        val jsonText = extractJsonBlock(rawPayload)
        val payload = json.parseToJsonElement(jsonText)
        val jobs = when (payload) {
            is JsonArray -> payload
            is JsonObject -> payload["jobs"] as? JsonArray
                ?: payload["schedules"] as? JsonArray
                ?: payload["entries"] as? JsonArray
                ?: error("No scheduler jobs array found in response")
            else -> error("Unsupported scheduler response")
        }

        return jobs.map { jobElement ->
            val job = jobElement.jsonObject
            ScheduledJobResponse(
                id = job.stringValue("id"),
                name = job.stringValue("name"),
                goal = job.stringValue("goal"),
                cronExpression = job.stringValue("cronExpression", "schedule", "cron"),
                enabled = job.booleanValue("enabled"),
                createdAt = job.longValue("createdAt"),
                nextRunAt = job.longValue("nextRunAt"),
            )
        }
    }

    private fun extractJsonBlock(input: String): String {
        val trimmed = input.trim()
        val withoutFence = trimmed
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        val objectStart = withoutFence.indexOf('{')
        val arrayStart = withoutFence.indexOf('[')
        val start = listOf(objectStart, arrayStart).filter { it >= 0 }.minOrNull()
            ?: error("No JSON payload found")
        val end = maxOf(withoutFence.lastIndexOf('}'), withoutFence.lastIndexOf(']'))
        if (end <= start) error("No complete JSON payload found")
        return withoutFence.substring(start, end + 1)
    }

    private fun JsonObject.stringValue(vararg keys: String): String =
        keys.firstNotNullOfOrNull { key -> this[key]?.jsonPrimitive?.contentOrNull }.orEmpty()

    private fun JsonObject.booleanValue(key: String): Boolean =
        this[key]?.jsonPrimitive?.booleanOrNull ?: false

    private fun JsonObject.longValue(key: String): Long =
        this[key]?.jsonPrimitive?.longOrNull ?: 0L
}

@Serializable
private data class ToolsResponse(
    val tools: List<ToolSchemaResponse>,
)

@Serializable
private data class ToolSchemaResponse(
    val name: String,
    val description: String,
    val parameters: ToolParametersSchemaResponse,
)

@Serializable
private data class ToolParametersSchemaResponse(
    val properties: Map<String, ToolParameterSchemaResponse>,
)

@Serializable
private data class ToolParameterSchemaResponse(
    val description: String,
)

@Serializable
private data class MemoryEntriesResponse(
    val entries: List<MemoryEntryResponse>,
)

@Serializable
private data class MemoryEntryResponse(
    val key: String,
    val value: String,
)
