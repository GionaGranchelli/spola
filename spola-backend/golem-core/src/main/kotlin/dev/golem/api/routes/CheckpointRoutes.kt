package dev.spola.api

import dev.spola.GolemConfig
import dev.spola.api.CheckpointDiffResponse
import dev.spola.api.CheckpointItemResponse
import dev.spola.api.CheckpointListResponse
import dev.spola.api.CheckpointMessageResponse
import dev.spola.api.CheckpointResumeResponse
import dev.spola.checkpoint.CheckpointManager
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.apiCheckpointRoutes(
    config: GolemConfig,
    checkpointManager: CheckpointManager,
) {
    get("/checkpoint") {
        call.enforceBearerAuth(config.apiKey)
        val checkpoints = checkpointManager.list()
        call.respond(CheckpointListResponse(checkpoints = checkpoints.map { it.toResponse() }))
    }

    get("/checkpoint/{id}/diff") {
        call.enforceBearerAuth(config.apiKey)
        val idStr = call.parameters["id"] ?: throw IllegalArgumentException("missing checkpoint id")
        val id = idStr.toLongOrNull() ?: throw IllegalArgumentException("invalid checkpoint id: $idStr")
        val cp = checkpointManager.loadCheckpoint(id)
            ?: throw IllegalArgumentException("checkpoint not found: $id")
        call.respond(
            CheckpointDiffResponse(
                id = cp.id,
                sessionId = cp.sessionId,
                turnNumber = cp.turnNumber,
                createdAt = cp.createdAt,
                diff = cp.diff,
            ),
        )
    }

    get("/checkpoint/session/{sessionId}/diffs") {
        call.enforceBearerAuth(config.apiKey)
        val sessionId = call.parameters["sessionId"] ?: throw IllegalArgumentException("missing session id")
        val checkpoints = checkpointManager.listForSession(sessionId)
        call.respond(
            CheckpointListResponse(
                checkpoints = checkpoints.map { cp ->
                    CheckpointItemResponse(
                        id = cp.id,
                        sessionId = cp.sessionId,
                        turnNumber = cp.turnNumber,
                        createdAt = cp.createdAt,
                        diff = cp.diff,
                    )
                },
            ),
        )
    }

    get("/checkpoint/resume/{session_id}") {
        call.enforceBearerAuth(config.apiKey)
        val sessionId = call.parameters["session_id"] ?: throw IllegalArgumentException("missing session_id")
        val loaded = checkpointManager.loadConversation(sessionId)
        if (loaded == null) {
            call.respond(
                HttpStatusCode.NotFound,
                mapOf("error" to "No checkpoint found for session: $sessionId"),
            )
        } else {
            call.respond(CheckpointResumeResponse(
                sessionId = sessionId,
                messageCount = loaded.size,
                messages = loaded.map { CheckpointMessageResponse(role = it.role, content = it.content) },
            ))
        }
    }
}
