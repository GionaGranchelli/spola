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
        call.enforceBearerAuth(config.apiKey)
        call.respond(sessionStore.list())
    }

    post("/session") {
        call.enforceBearerAuth(config.apiKey)
        val request = call.receive<CreateSessionRequest>()
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val session = SessionInfo(
            id = id,
            title = request.title,
            createdAt = now,
            lastActiveAt = now,
            modelId = request.modelId ?: config.model,
            providerId = request.providerId ?: config.provider,
        )
        sessionStore.create(session)
        call.respond(session)
    }

    delete("/session/{id}") {
        call.enforceBearerAuth(config.apiKey)
        val id = call.parameters["id"] ?: throw IllegalArgumentException("missing session id")
        sessionStore.delete(id)
        call.respond(HttpStatusCode.NoContent)
    }

    get("/session/{id}") {
        call.enforceBearerAuth(config.apiKey)
        val id = call.parameters["id"] ?: throw IllegalArgumentException("missing session id")
        val session = sessionStore.get(id) ?: throw IllegalArgumentException("session not found: $id")
        call.respond(session)
    }

    post("/session/{id}/model") {
        call.enforceBearerAuth(config.apiKey)
        val id = call.parameters["id"] ?: throw IllegalArgumentException("missing session id")
        val request = call.receive<SessionModelUpdate>()
        val existing = sessionStore.get(id) ?: throw IllegalArgumentException("session not found: $id")
        val updated = existing.copy(modelId = request.modelId, lastActiveAt = System.currentTimeMillis())
        sessionStore.update(updated)
        call.respond(updated)
    }

    get("/session/{id}/messages") {
        call.enforceBearerAuth(config.apiKey)
        val id = call.parameters["id"] ?: throw IllegalArgumentException("missing session id")
        sessionStore.get(id) ?: throw IllegalArgumentException("session not found: $id")
        call.respond(
            sessionStore.getMessages(id).map { message ->
                CheckpointMessageResponse(role = message.role, content = message.content)
            },
        )
    }

    post("/session/{id}/run") {
        call.enforceBearerAuth(config.apiKey)
        val id = call.parameters["id"] ?: throw IllegalArgumentException("missing session id")
        val existing = sessionStore.get(id) ?: throw IllegalArgumentException("session not found: $id")
        val request = call.receive<AgentRunRequest>()
        val completedRun = agentRunHandler.runWithConversation(
            request.copy(model = request.model ?: existing.modelId),
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
        call.enforceBearerAuth(config.apiKey)
        val id = call.parameters["id"] ?: throw IllegalArgumentException("missing session id")
        val existing = sessionStore.get(id) ?: throw IllegalArgumentException("session not found: $id")
        val request = call.receive<AgentRunRequest>()
        val effectiveRequest = request.copy(model = request.model ?: existing.modelId)
        call.respond(SSEServerContent(call) {
            streamHandler.stream(this, effectiveRequest) { completedRun ->
                sessionStore.replaceMessages(id, completedRun.conversation)
                sessionStore.update(existing.copy(lastActiveAt = System.currentTimeMillis()))
            }
        })
    }

    get("/models") {
        call.enforceBearerAuth(config.apiKey)
        call.respond(
            listOf(
                ModelInfo(
                    id = config.model,
                    name = config.model,
                    provider = config.provider,
                    description = "${config.provider} model configured in Spola",
                ),
            ),
        )
    }
}
