package dev.spola.api

import dev.spola.SpolaConfig
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
    config: SpolaConfig,
    checkpointManager: CheckpointManager,
) {
    get("/checkpoint") {
        call.enforceBearerAuth(config.security.apiKey)
        val checkpoints = checkpointManager.list()
        call.respond(CheckpointListResponse(checkpoints = checkpoints.map { it.toResponse() }))
    }

    get("/checkpoint/{id}/diff") {
        call.enforceBearerAuth(config.security.apiKey)
        val idStr = call.requirePathParameter("id", "checkpoint id")
        val id = idStr.toRequiredLong("checkpoint id")
        val cp = checkpointManager.loadCheckpoint(id).orNotFound { "checkpoint not found: $id" }
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
        call.enforceBearerAuth(config.security.apiKey)
        val sessionId = call.requirePathParameter("sessionId", "session id")
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
        call.enforceBearerAuth(config.security.apiKey)
        val sessionId = call.requirePathParameter("session_id")
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
