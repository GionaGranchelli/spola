package dev.spola.api

import dev.spola.GolemConfig
import dev.spola.api.DeleteMemoryResponse
import dev.spola.api.MemoryEntriesResponse
import dev.spola.memory.MemoryStore
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get

fun Route.apiMemoryRoutes(
    config: GolemConfig,
    memoryStore: MemoryStore,
) {
    get("/memory") {
        call.enforceBearerAuth(config.apiKey)
        val query = call.request.queryParameters["q"]?.trim().orEmpty()
        val entries = if (query.isBlank()) {
            memoryStore.listAll()
        } else {
            memoryStore.search(query)
        }
        call.respond(
            MemoryEntriesResponse(
                entries = entries.map { it.toResponse() },
                query = query.takeIf { it.isNotBlank() },
            ),
        )
    }

    delete("/memory/{key}") {
        call.enforceBearerAuth(config.apiKey)
        val key = call.parameters["key"] ?: throw IllegalArgumentException("missing memory key")
        val deleted = memoryStore.delete(key)
        if (!deleted) {
            call.respond(
                HttpStatusCode.NotFound,
                DeleteMemoryResponse(
                    deleted = false,
                    key = key,
                    message = "Memory entry not found",
                ),
            )
        } else {
            call.respond(
                DeleteMemoryResponse(
                    deleted = true,
                    key = key,
                    message = "Memory entry deleted",
                ),
            )
        }
    }
}
