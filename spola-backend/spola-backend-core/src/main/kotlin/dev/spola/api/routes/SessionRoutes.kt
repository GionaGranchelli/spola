package dev.spola.api

import dev.spola.SpolaConfig
import dev.spola.api.AgentRunHandler
import dev.spola.api.AgentRunRequest
import dev.spola.api.AgentRunResponse
import dev.spola.api.CheckpointMessageResponse
import dev.spola.api.CreateSessionRequest
import dev.spola.api.ExecRequest
import dev.spola.api.ModelInfo
import dev.spola.api.PinRequest
import dev.spola.api.PinResponse
import dev.spola.api.SessionInfo
import dev.spola.api.SessionModelUpdate
import dev.spola.api.SqliteSessionStore
import dev.spola.api.StreamHandler
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.request.receive
import io.ktor.server.sse.SSEServerContent
import io.ktor.sse.ServerSentEvent
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val execJson = Json { encodeDefaults = true }

fun Route.apiSessionRoutes(
    config: SpolaConfig,
    sessionStore: SqliteSessionStore,
    agentRunHandler: AgentRunHandler,
    streamHandler: StreamHandler,
) {
    val sessionWorkdirs = ConcurrentHashMap<String, String>()

    get("/sessions") {
        call.enforceBearerAuth(config.security.apiKey, insecure = config.security.insecure)
        call.respond(sessionStore.list())
    }

    post("/session") {
        call.enforceBearerAuth(config.security.apiKey, insecure = config.security.insecure)
        val request = call.receive<CreateSessionRequest>()
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val session = SessionInfo(
            id = id,
            title = request.title,
            createdAt = now,
            lastActiveAt = now,
            modelId = request.modelId ?: config.provider.defaultModel,
            providerId = request.providerId ?: config.provider.defaultProvider,
            pinnedMessageIds = emptySet(),
        )
        sessionStore.create(session)
        call.respond(session)
    }

    delete("/session/{id}") {
        call.enforceBearerAuth(config.security.apiKey, insecure = config.security.insecure)
        val id = call.requirePathParameter("id", "session id")
        sessionWorkdirs.remove(id)
        sessionStore.delete(id)
        call.respond(HttpStatusCode.NoContent)
    }

    get("/session/{id}") {
        call.enforceBearerAuth(config.security.apiKey, insecure = config.security.insecure)
        val id = call.requirePathParameter("id", "session id")
        val session = sessionStore.get(id).orNotFound { "session not found: $id" }
        call.respond(session)
    }

    post("/session/{id}/model") {
        call.enforceBearerAuth(config.security.apiKey, insecure = config.security.insecure)
        val id = call.requirePathParameter("id", "session id")
        val request = call.receive<SessionModelUpdate>()
        val existing = sessionStore.get(id).orNotFound { "session not found: $id" }
        val updated = existing.copy(modelId = request.modelId, lastActiveAt = System.currentTimeMillis())
        sessionStore.update(updated)
        call.respond(updated)
    }

    get("/session/{id}/messages") {
        call.enforceBearerAuth(config.security.apiKey, insecure = config.security.insecure)
        val id = call.requirePathParameter("id", "session id")
        sessionStore.get(id).orNotFound { "session not found: $id" }
        call.respond(
            sessionStore.getMessages(id).map { message ->
                CheckpointMessageResponse(role = message.role, content = message.content)
            },
        )
    }

    post("/sessions/{id}/pin") {
        call.enforceBearerAuth(config.security.apiKey, insecure = config.security.insecure)
        val id = call.requirePathParameter("id", "session id")
        val existing = sessionStore.get(id).orNotFound { "session not found: $id" }
        val request = call.receive<PinRequest>()
        val updated = existing.copy(
            pinnedMessageIds = request.messageIds.toSet(),
            lastActiveAt = System.currentTimeMillis(),
        )
        sessionStore.update(updated)
        call.respond(PinResponse(messageIds = updated.pinnedMessageIds.sorted()))
    }

    get("/sessions/{id}/pin") {
        call.enforceBearerAuth(config.security.apiKey, insecure = config.security.insecure)
        val id = call.requirePathParameter("id", "session id")
        val existing = sessionStore.get(id).orNotFound { "session not found: $id" }
        call.respond(PinResponse(messageIds = existing.pinnedMessageIds.sorted()))
    }

    post("/session/{id}/run") {
        call.enforceBearerAuth(config.security.apiKey, insecure = config.security.insecure)
        val id = call.requirePathParameter("id", "session id")
        val existing = sessionStore.get(id).orNotFound { "session not found: $id" }
        val request = call.receive<AgentRunRequest>()
        val existingMessages = sessionStore.getMessages(id)
        val completedRun = agentRunHandler.runWithConversation(
            request.copy(model = request.model ?: existing.modelId),
            preloadedConversation = existingMessages,
            pinnedMessageIds = existing.pinnedMessageIds,
        )
        sessionStore.replaceMessages(id, completedRun.conversation)
        sessionStore.update(existing.copy(lastActiveAt = System.currentTimeMillis()))
        call.respond(
            AgentRunResponse(
                result = completedRun.result,
                turns = completedRun.turns,
            ),
        )
    }

    post("/session/{id}/run/stream") {
        call.enforceBearerAuth(config.security.apiKey, insecure = config.security.insecure)
        val id = call.requirePathParameter("id", "session id")
        val existing = sessionStore.get(id).orNotFound { "session not found: $id" }
        val request = call.receive<AgentRunRequest>()
        val effectiveRequest = request.copy(model = request.model ?: existing.modelId)
        val existingMessages = sessionStore.getMessages(id)
        call.respond(SSEServerContent(call) {
            streamHandler.stream(this, effectiveRequest, existingMessages, existing.pinnedMessageIds) { completedRun ->
                sessionStore.replaceMessages(id, completedRun.conversation)
                sessionStore.update(existing.copy(lastActiveAt = System.currentTimeMillis()))
            }
        })
    }

    post("/session/{id}/exec/stream") {
        call.enforceBearerAuth(config.security.apiKey, insecure = config.security.insecure)
        val id = call.requirePathParameter("id", "session id")
        val existing = sessionStore.get(id).orNotFound { "session not found: $id" }
        sessionStore.update(existing.copy(lastActiveAt = System.currentTimeMillis()))
        val request = call.receive<ExecRequest>()
        call.respond(SSEServerContent(call) {
            val workdir = sessionWorkdirs.getOrDefault(id, config.workingDirectory)
            val commandStr = request.command.trim()

            if (commandStr.startsWith("cd ")) {
                val newDir = commandStr.removePrefix("cd").trim()
                if (newDir.isBlank()) {
                    send(ServerSentEvent(
                        data = execJson.encodeToString(buildJsonObject { put("result", workdir); put("turns", 0) }),
                        event = "complete",
                    ))
                    return@SSEServerContent
                }
                val resolvedPath = java.io.File(workdir).resolve(newDir).normalize().absolutePath
                val dirFile = java.io.File(resolvedPath)
                if (!dirFile.exists() || !dirFile.isDirectory) {
                    send(ServerSentEvent(
                        data = execJson.encodeToString(buildJsonObject { put("error", "Directory not found: $resolvedPath") }),
                        event = "error",
                    ))
                } else {
                    sessionWorkdirs[id] = resolvedPath
                    send(ServerSentEvent(
                        data = execJson.encodeToString(buildJsonObject { put("text", "✓ Working directory changed to: $resolvedPath") }),
                        event = "token",
                    ))
                    send(ServerSentEvent(
                        data = execJson.encodeToString(buildJsonObject { put("result", ""); put("turns", 0) }),
                        event = "complete",
                    ))
                }
                return@SSEServerContent
            }

            val result = try {
                dev.spola.tools.executeShellCommand(
                    commandStr = commandStr,
                    workdirStr = workdir,
                    timeoutSec = 300,
                    maxOutputSize = 51200,
                )
            } catch (e: Exception) {
                send(ServerSentEvent(
                    data = execJson.encodeToString(buildJsonObject { put("error", e.message ?: "Shell execution failed") }),
                    event = "error",
                ))
                return@SSEServerContent
            }

            send(ServerSentEvent(
                data = execJson.encodeToString(buildJsonObject { put("text", result.output) }),
                event = "token",
            ))

            if (result.success) {
                send(ServerSentEvent(
                    data = execJson.encodeToString(buildJsonObject { put("result", ""); put("turns", 0) }),
                    event = "complete",
                ))
            } else {
                send(ServerSentEvent(
                    data = execJson.encodeToString(buildJsonObject { put("error", result.output) }),
                    event = "error",
                ))
            }
        })
    }

    get("/models") {
        call.enforceBearerAuth(config.security.apiKey, insecure = config.security.insecure)
        call.respond(
            listOf(
                ModelInfo(
                    id = config.provider.defaultModel,
                    name = config.provider.defaultModel,
                    provider = config.provider.defaultProvider,
                    description = "${config.provider.defaultProvider} model configured in Spola",
                ),
            ),
        )
    }
}
