package dev.spola.api

import dev.spola.SpolaConfig
import dev.spola.api.AgentRunHandler
import dev.spola.api.AgentRunRequest
import dev.spola.api.AgentRunResponse
import dev.spola.api.CheckpointMessageResponse
import dev.spola.api.CreateSessionRequest
import dev.spola.api.ModelInfo
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
import java.util.UUID

fun Route.apiSessionRoutes(
    config: SpolaConfig,
    sessionStore: SqliteSessionStore,
    agentRunHandler: AgentRunHandler,
    streamHandler: StreamHandler,
) {
    get("/sessions") {
        call.enforceBearerAuth(config.security.apiKey)
        call.respond(sessionStore.list())
    }

    post("/session") {
        call.enforceBearerAuth(config.security.apiKey)
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
        )
        sessionStore.create(session)
        call.respond(session)
    }

    delete("/session/{id}") {
        call.enforceBearerAuth(config.security.apiKey)
        val id = call.requirePathParameter("id", "session id")
        sessionStore.delete(id)
        call.respond(HttpStatusCode.NoContent)
    }

    get("/session/{id}") {
        call.enforceBearerAuth(config.security.apiKey)
        val id = call.requirePathParameter("id", "session id")
        val session = sessionStore.get(id).orNotFound { "session not found: $id" }
        call.respond(session)
    }

    post("/session/{id}/model") {
        call.enforceBearerAuth(config.security.apiKey)
        val id = call.requirePathParameter("id", "session id")
        val request = call.receive<SessionModelUpdate>()
        val existing = sessionStore.get(id).orNotFound { "session not found: $id" }
        val updated = existing.copy(modelId = request.modelId, lastActiveAt = System.currentTimeMillis())
        sessionStore.update(updated)
        call.respond(updated)
    }

    get("/session/{id}/messages") {
        call.enforceBearerAuth(config.security.apiKey)
        val id = call.requirePathParameter("id", "session id")
        sessionStore.get(id).orNotFound { "session not found: $id" }
        call.respond(
            sessionStore.getMessages(id).map { message ->
                CheckpointMessageResponse(role = message.role, content = message.content)
            },
        )
    }

    post("/session/{id}/run") {
        call.enforceBearerAuth(config.security.apiKey)
        val id = call.requirePathParameter("id", "session id")
        val existing = sessionStore.get(id).orNotFound { "session not found: $id" }
        val request = call.receive<AgentRunRequest>()
        val existingMessages = sessionStore.getMessages(id)
        val completedRun = agentRunHandler.runWithConversation(
            request.copy(model = request.model ?: existing.modelId),
            preloadedConversation = existingMessages,
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
        call.enforceBearerAuth(config.security.apiKey)
        val id = call.requirePathParameter("id", "session id")
        val existing = sessionStore.get(id).orNotFound { "session not found: $id" }
        val request = call.receive<AgentRunRequest>()
        val effectiveRequest = request.copy(model = request.model ?: existing.modelId)
        val existingMessages = sessionStore.getMessages(id)
        call.respond(SSEServerContent(call) {
            streamHandler.stream(this, effectiveRequest, existingMessages) { completedRun ->
                sessionStore.replaceMessages(id, completedRun.conversation)
                sessionStore.update(existing.copy(lastActiveAt = System.currentTimeMillis()))
            }
        })
    }

    get("/models") {
        call.enforceBearerAuth(config.security.apiKey)
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
