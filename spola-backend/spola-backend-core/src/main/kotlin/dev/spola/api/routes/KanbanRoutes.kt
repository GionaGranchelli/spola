package dev.spola.api

import dev.spola.SpolaConfig
import dev.spola.kanban.KanbanStore
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.request.receive
import kotlinx.serialization.Serializable

@Serializable
data class KanbanCardResponse(
    val id: String,
    val text: String,
    val status: String,
    val createdAt: Long,
)

@Serializable
data class KanbanCreateRequest(val text: String)

@Serializable
data class KanbanUpdateRequest(val text: String, val status: String)

fun Route.apiKanbanRoutes(
    config: SpolaConfig,
    kanbanStore: KanbanStore,
) {
    get("/kanban") {
        call.enforceBearerAuth(config.security.apiKey, insecure = config.security.insecure)
        val cards = kanbanStore.list().map { task ->
            KanbanCardResponse(
                id = task.id,
                text = task.title,
                status = task.status,
                createdAt = task.createdAt,
            )
        }
        call.respond(cards)
    }

    post("/kanban") {
        call.enforceBearerAuth(config.security.apiKey, insecure = config.security.insecure)
        val req = call.receive<KanbanCreateRequest>()
        val task = kanbanStore.create(title = req.text)
        call.respond(
            HttpStatusCode.Created,
            KanbanCardResponse(
                id = task.id,
                text = task.title,
                status = task.status,
                createdAt = task.createdAt,
            ),
        )
    }

    put("/kanban/{id}") {
        call.enforceBearerAuth(config.security.apiKey, insecure = config.security.insecure)
        val id = call.parameters["id"] ?: throw IllegalArgumentException("missing card id")
        val req = call.receive<KanbanUpdateRequest>()
        val task = kanbanStore.update(
            id = id,
            title = req.text,
            status = req.status,
        ) ?: throw IllegalArgumentException("card not found: $id")
        call.respond(
            KanbanCardResponse(
                id = task.id,
                text = task.title,
                status = task.status,
                createdAt = task.createdAt,
            ),
        )
    }

    delete("/kanban/{id}") {
        call.enforceBearerAuth(config.security.apiKey, insecure = config.security.insecure)
        val id = call.parameters["id"] ?: throw IllegalArgumentException("missing card id")
        val removed = kanbanStore.remove(id)
        if (!removed) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "card not found"))
        } else {
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
