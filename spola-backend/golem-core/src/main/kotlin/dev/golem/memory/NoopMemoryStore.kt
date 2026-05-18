package dev.spola.memory

/**
 * A MemoryStore implementation that does nothing.
 * Used when an agent's memoryScope is "none" — prevents
 * the agent from reading or writing any memory.
 */
class NoopMemoryStore : MemoryStore {
    override suspend fun save(key: String, value: String) {
        // No-op
    }

    override suspend fun search(query: String): List<MemoryEntry> = emptyList()

    override suspend fun listAll(): List<MemoryEntry> = emptyList()

    override suspend fun delete(key: String): Boolean = false

    override fun close() { /* no-op */ }
}
