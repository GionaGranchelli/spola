package dev.spola.app.backend.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import dev.spola.app.backend.BackendServices
import dev.spola.app.backend.TrustAuth
import dev.spola.app.models.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.UUID

fun Route.sessionRoutes(services: BackendServices) {
    get("/sessions") {
        if (TrustAuth.requireTrust(call, services.stateStore) == null) return@get
        call.respond(services.sessionRepository.getAll())
    }

    get("/session/{id}") {
        if (TrustAuth.requireTrust(call, services.stateStore) == null) return@get
        val sessionId = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val session = services.sessionRepository.getById(sessionId)
            ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respond(session)
    }

    get("/session/{id}/messages") {
        if (TrustAuth.requireTrust(call, services.stateStore) == null) return@get
        val sessionId = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        call.respond(services.messageRepository.getBySessionId(sessionId))
    }

    post("/session") {
        if (TrustAuth.requireTrust(call, services.stateStore) == null) return@post
        val session = call.receive<ChatSession>()
        println(
            "[SessionRoutes] create session request id=${session.id} title=${session.title} " +
                "modelId=${session.modelId} providerId=${session.providerId}"
        )
        services.sessionRepository.create(session)
        println("[SessionRoutes] create session persisted id=${session.id}")
        services.auditRepository.log("session.create", sessionId = session.id, details = session.title)
        services.flowManager.emitSystemEvent(SystemEvent(SystemEventType.SESSIONS_CHANGED))
        call.respond(session)
    }

    delete("/session/{id}") {
        if (TrustAuth.requireTrust(call, services.stateStore) == null) return@delete
        val sessionId = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
        services.sessionRepository.delete(sessionId)
        services.auditRepository.log("session.delete", sessionId = sessionId)
        services.flowManager.emitSystemEvent(SystemEvent(SystemEventType.SESSIONS_CHANGED))
        call.respond(HttpStatusCode.NoContent)
    }

    post("/session/{id}/model") {
        if (TrustAuth.requireTrust(call, services.stateStore) == null) return@post
        val sessionId = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        val request = call.receive<SessionModelUpdateRequest>()
        services.sessionRepository.updateModel(sessionId, request.modelId)
        val updated = services.sessionRepository.getById(sessionId) ?: return@post call.respond(HttpStatusCode.NotFound)
        services.auditRepository.log("session.model.update", sessionId = sessionId, details = request.modelId)
        call.respond(updated)
    }

    post("/session/{id}/provider") {
        if (TrustAuth.requireTrust(call, services.stateStore) == null) return@post
        val sessionId = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        val request = call.receive<SessionProviderUpdateRequest>()
        services.sessionRepository.saveProvider(sessionId, request.providerId)
        val updated = services.sessionRepository.getById(sessionId) ?: return@post call.respond(HttpStatusCode.NotFound)
        services.auditRepository.log("session.provider.update", sessionId = sessionId, details = request.providerId)
        call.respond(updated)
    }

    get("/session/{id}/openclaw") {
        if (TrustAuth.requireTrust(call, services.stateStore) == null) return@get
        val sessionId = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        call.respond(services.sessionRepository.getOpenClawSettings(sessionId))
    }

    post("/session/{id}/openclaw") {
        if (TrustAuth.requireTrust(call, services.stateStore) == null) return@post
        val sessionId = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        val settings = call.receive<OpenClawSessionSettings>()
        services.sessionRepository.saveOpenClawSettings(sessionId, settings)
        services.auditRepository.log(
            kind = "session.openclaw.update",
            sessionId = sessionId,
            details = "agent=${settings.agentId ?: "-"} model=${settings.modelId ?: "-"} mode=${settings.mode ?: "-"} thinking=${settings.thinking ?: "-"}",
        )
        call.respond(settings)
    }

    post("/session/{id}/message") {
        if (TrustAuth.requireTrust(call, services.stateStore) == null) return@post
        val sessionId = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        val content = call.receiveText()
        val userMsg = services.chatRoutingService.persistUserMessage(sessionId, content)
        services.auditRepository.log("message.send", sessionId = sessionId, details = content.take(120))
        val flow = services.flowManager.getSessionFlow(sessionId)
        application.launch(Dispatchers.IO) {
            try {
                services.chatRoutingService.generateAssistantReply(
                    sessionId = sessionId,
                    prompt = content,
                    onStatus = { status -> flow.tryEmit(StreamEvent(StreamEventType.status, status)) },
                    onToken = { token -> flow.tryEmit(StreamEvent(StreamEventType.token, token)) }
                )
            } catch (e: Exception) {
                flow.tryEmit(StreamEvent(StreamEventType.error, e.message))
            }
        }
        call.respond(userMsg)
    }

    sse("/session/{id}/stream") {
        if (TrustAuth.requireTrust(call, services.stateStore) == null) return@sse
        val sessionId = call.parameters["id"] ?: return@sse
        val flow = services.flowManager.getSessionFlow(sessionId)
        try {
            flow.collect { send(data = Json.encodeToString(StreamEvent.serializer(), it)) }
        } catch (e: Exception) {
            // Likely client disconnect
        }
    }

    sse("/system/stream") {
        if (TrustAuth.requireTrust(call, services.stateStore) == null) return@sse
        val flow = services.flowManager.systemFlow
        try {
            flow.collect { send(data = Json.encodeToString(SystemEvent.serializer(), it)) }
        } catch (e: Exception) {
            // Client disconnect
        }
    }

    route("api") {
        get("/kanban") {
            if (TrustAuth.requireTrust(call, services.stateStore) == null) return@get
            call.respond(services.kanbanCards.sortedByDescending(KanbanCard::createdAt))
        }

        post("/kanban") {
            if (TrustAuth.requireTrust(call, services.stateStore) == null) return@post
            val request = call.receive<KanbanCardCreateRequest>()
            val text = request.text.trim()
            if (text.isBlank()) {
                return@post call.respond(HttpStatusCode.BadRequest, "Card text cannot be blank")
            }
            val card = KanbanCard(
                id = UUID.randomUUID().toString(),
                text = text,
                status = "todo",
                createdAt = System.currentTimeMillis(),
            )
            services.kanbanCards += card
            call.respond(card)
        }

        put("/kanban/{id}") {
            if (TrustAuth.requireTrust(call, services.stateStore) == null) return@put
            val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest)
            val request = call.receive<KanbanCardUpdateRequest>()
            val index = services.kanbanCards.indexOfFirst { it.id == id }
            if (index == -1) {
                return@put call.respond(HttpStatusCode.NotFound)
            }
            val updated = services.kanbanCards[index].copy(
                text = request.text.trim().ifBlank { services.kanbanCards[index].text },
                status = request.status,
            )
            services.kanbanCards[index] = updated
            call.respond(updated)
        }

        delete("/kanban/{id}") {
            if (TrustAuth.requireTrust(call, services.stateStore) == null) return@delete
            val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
            val removed = services.kanbanCards.removeIf { it.id == id }
            if (!removed) {
                return@delete call.respond(HttpStatusCode.NotFound)
            }
            call.respond(HttpStatusCode.NoContent)
        }

        get("/workflows") {
            if (TrustAuth.requireTrust(call, services.stateStore) == null) return@get
            call.respond(services.workflowDefinitions.toList())
        }

        post("/workflows/{id}/toggle") {
            if (TrustAuth.requireTrust(call, services.stateStore) == null) return@post
            val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val request = call.receive<WorkflowToggleRequest>()
            val index = services.workflowDefinitions.indexOfFirst { it.id == id }
            if (index == -1) {
                return@post call.respond(HttpStatusCode.NotFound)
            }
            services.workflowDefinitions[index] = services.workflowDefinitions[index].copy(enabled = request.enabled)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
