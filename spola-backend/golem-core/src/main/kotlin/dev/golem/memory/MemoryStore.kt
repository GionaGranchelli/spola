package dev.spola.memory

/**
 * Persistent key-value store for agent memory.
 */
interface MemoryStore : AutoCloseable {
    /** Save a fact. Upserts if key already exists. */
    suspend fun save(key: String, value: String)

    /** Search memory by key or value content. Returns formatted results. */
    suspend fun search(query: String): List<MemoryEntry>

    /** List all entries. */
    suspend fun listAll(): List<MemoryEntry>

    /** Delete an entry by key. */
    suspend fun delete(key: String): Boolean
}

data class MemoryEntry(
    val key: String,
    val value: String,
    val createdAt: String,
    val updatedAt: String,
)
