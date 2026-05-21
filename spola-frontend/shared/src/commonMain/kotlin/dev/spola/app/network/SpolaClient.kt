package dev.spola.app.network

import dev.spola.app.models.ChatSession
import dev.spola.app.models.Message
import dev.spola.app.models.ModelInfo
import dev.spola.app.models.PairingInfoResponse
import dev.spola.app.models.StreamEvent
import dev.spola.app.models.StreamEventType
import dev.spola.app.models.SystemEvent
import dev.spola.models.AgentRunRequest
import dev.spola.models.AgentRunResponse
import dev.spola.models.ScheduledJobResponse
import dev.spola.models.ToolInfo
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.client.plugins.timeout
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SpolaClient(
    private val httpClient: HttpClient,
    private val baseUrl: String = "http://localhost:8082",
    private val authToken: String? = null,
) {
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
            requestTimeoutMillis = 600000
            connectTimeoutMillis = 20000
            socketTimeoutMillis = 600000
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
                            cause,
                        )
                    }
                }
            }
        }
        defaultRequest {
            url(normalizedBaseUrl)
            contentType(ContentType.Application.Json)
            authToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        }
    }

    val sessionClient = SessionClient(client, json)
    val kanbanClient = KanbanClient(client)
    val workflowClient = WorkflowClient(client, json)
    val memoryClient = MemoryClient(client)
    val schedulerClient = SchedulerClient(client, json, ::runAgent)

    fun streamEvents(sessionId: String): Flow<StreamEvent> = sessionClient.streamEvents(sessionId)

    fun streamSystemEvents(): Flow<SystemEvent> =
        sessionClient.streamWithRetry("api/system/stream") { _, data -> json.decodeFromString<SystemEvent>(data) }

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
            },
        ) {
            incoming.collect { event ->
                val data = event.data ?: return@collect
                val type = event.event ?: return@collect
                emit(parseAgentRunEvent(type, data))
            }
        }
    }

    suspend fun getSessions(): List<ChatSession> = sessionClient.getSessions()
    suspend fun getSession(sessionId: String): ChatSession = sessionClient.getSession(sessionId)
    suspend fun getMessages(sessionId: String): List<Message> = sessionClient.getMessages(sessionId)
    suspend fun createSession(session: ChatSession): ChatSession = sessionClient.createSession(session)
    suspend fun deleteSession(id: String) = sessionClient.deleteSession(id)
    suspend fun sendMessage(sessionId: String, content: String): Message = sessionClient.sendMessage(sessionId, content)
    suspend fun uploadSessionFile(sessionId: String, fileName: String, fileBytes: ByteArray): String =
        sessionClient.uploadSessionFile(sessionId, fileName, fileBytes)
    suspend fun sendSessionMessage(sessionId: String, text: String, fileRef: String? = null) =
        sessionClient.sendSessionMessage(sessionId, text, fileRef)
    suspend fun getModels(): List<ModelInfo> = sessionClient.getModels()
    suspend fun updateSessionModel(sessionId: String, modelId: String): ChatSession =
        sessionClient.updateSessionModel(sessionId, modelId)

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

    suspend fun searchMemory(query: String): List<Pair<String, String>> = memoryClient.searchMemory(query)
    suspend fun deleteMemory(key: String) = memoryClient.deleteMemory(key)

    suspend fun fetchPairingInfo(serverUrl: String): PairingInfoResponse {
        val url = serverUrl.trimEnd('/') + "/api/pairing/info"
        return client.get(url).body()
    }

    suspend fun getScheduledJobs(): List<ScheduledJobResponse> = schedulerClient.getScheduledJobs()
    suspend fun getKanbanCards() = kanbanClient.getKanbanCards()
    suspend fun createKanbanCard(text: String) = kanbanClient.createKanbanCard(text)
    suspend fun updateKanbanCard(id: String, text: String, status: String) =
        kanbanClient.updateKanbanCard(id, text, status)
    suspend fun deleteKanbanCard(id: String) = kanbanClient.deleteKanbanCard(id)
    suspend fun getWorkflowDefinitions() = workflowClient.getWorkflowDefinitions()
    suspend fun createWorkflowDefinition(name: String, description: String) =
        workflowClient.createWorkflowDefinition(name, description)
    suspend fun updateWorkflowDefinition(id: String, name: String? = null, description: String? = null) =
        workflowClient.updateWorkflowDefinition(id, name, description)
    suspend fun deleteWorkflowDefinition(id: String) = workflowClient.deleteWorkflowDefinition(id)
    suspend fun toggleWorkflowDefinition(id: String, enabled: Boolean) =
        workflowClient.toggleWorkflowDefinition(id, enabled)
    suspend fun runWorkflow(
        workflowName: String,
        goal: String,
        definitionId: String? = null,
        sessionId: String? = null,
        inputJson: String = "{}",
    ) = workflowClient.runWorkflow(workflowName, goal, definitionId, sessionId, inputJson)
    suspend fun getWorkflowExecutions(limit: Int = 50) = workflowClient.getWorkflowExecutions(limit)
    suspend fun getWorkflowExecution(id: String) = workflowClient.getWorkflowExecution(id)

    fun close() {
        client.close()
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
}

internal fun isRecoverableSseDisconnect(error: Throwable): Boolean {
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
