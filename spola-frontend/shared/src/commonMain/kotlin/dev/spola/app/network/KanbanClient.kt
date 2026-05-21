package dev.spola.app.network

import dev.spola.app.models.KanbanCard
import dev.spola.app.models.KanbanCardCreateRequest
import dev.spola.app.models.KanbanCardUpdateRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.http.ContentType
import io.ktor.http.contentType

class KanbanClient(private val client: HttpClient) {
    suspend fun getKanbanCards(): List<KanbanCard> = client.get("api/kanban").body()

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
}
