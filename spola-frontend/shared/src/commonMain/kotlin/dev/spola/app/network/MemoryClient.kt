package dev.spola.app.network

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.Serializable

class MemoryClient(private val client: HttpClient) {
    suspend fun searchMemory(query: String): List<Pair<String, String>> =
        client.get("api/memory") {
            parameter("q", query)
        }.body<MemoryEntriesResponse>().entries.map { it.key to it.value }

    suspend fun deleteMemory(key: String) {
        client.delete("api/memory/$key")
    }
}

@Serializable
private data class MemoryEntriesResponse(
    val entries: List<MemoryEntryResponse>,
)

@Serializable
private data class MemoryEntryResponse(
    val key: String,
    val value: String,
)
