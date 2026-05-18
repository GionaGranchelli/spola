package dev.spola.memory

import dev.spola.sqlite.SqliteStoreSupport
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * SQLite-backed memory store using Exposed ORM.
 */
class SqliteMemoryStore(dbPath: String) : MemoryStore {
    private val database = SqliteStoreSupport.connectSqliteDatabase(dbPath)

    private val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    init {
        runBlocking {
            SqliteStoreSupport.retryingTransaction(database) {
                SchemaUtils.create(MemoryEntries)
            }
        }
    }

    object MemoryEntries : Table("memory_entries") {
        val id = integer("id").autoIncrement()
        val key = varchar("key", 512)
        val value = text("value")
        val createdAt = varchar("created_at", 32)
        val updatedAt = varchar("updated_at", 32)

        override val primaryKey = PrimaryKey(id)
        init {
            index(true, key) // unique index on key
        }
    }

    override suspend fun save(key: String, value: String) {
        val now = LocalDateTime.now().format(formatter)
        SqliteStoreSupport.retryingTransaction(database) {
            // Use primary key-based update to avoid race conditions
            val existing = MemoryEntries.selectAll().where { MemoryEntries.key eq key }
                .singleOrNull()

            if (existing != null) {
                val id = existing[MemoryEntries.id]
                MemoryEntries.update({ MemoryEntries.id eq id }) {
                    it[MemoryEntries.value] = value
                    it[MemoryEntries.updatedAt] = now
                }
            } else {
                MemoryEntries.insert {
                    it[MemoryEntries.key] = key
                    it[MemoryEntries.value] = value
                    it[createdAt] = now
                    it[updatedAt] = now
                }
            }
        }
    }

    override suspend fun search(query: String): List<MemoryEntry> {
        return SqliteStoreSupport.retryingTransaction(database) {
            MemoryEntries.selectAll()
                .where {
                    MemoryEntries.key like "%$query%" or
                    (MemoryEntries.value like "%$query%")
                }
                .orderBy(MemoryEntries.updatedAt, SortOrder.DESC)
                .limit(20)
                .map { rowToEntry(it) }
        }
    }

    override suspend fun listAll(): List<MemoryEntry> {
        return SqliteStoreSupport.retryingTransaction(database) {
            MemoryEntries.selectAll()
                .orderBy(MemoryEntries.updatedAt, SortOrder.DESC)
                .map { rowToEntry(it) }
        }
    }

    override suspend fun delete(key: String): Boolean {
        return SqliteStoreSupport.retryingTransaction(database) {
            val count = MemoryEntries.deleteWhere { MemoryEntries.key eq key }
            count > 0
        }
    }

    private fun rowToEntry(row: ResultRow) = MemoryEntry(
        key = row[MemoryEntries.key],
        value = row[MemoryEntries.value],
        createdAt = row[MemoryEntries.createdAt],
        updatedAt = row[MemoryEntries.updatedAt],
    )

    override fun close() {
        // SQLite connection pool is managed by Exposed
    }
}
