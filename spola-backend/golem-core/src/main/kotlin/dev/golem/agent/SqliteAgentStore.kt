package dev.spola.agent

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.Connection

/**
 * SQLite-backed AgentStore using Exposed.
 *
 * Uses its own [Database] instance (not the global one) so it doesn't
 * conflict with other stores (SqliteMemoryStore, SqliteKanbanStore, etc.)
 * that may connect to different SQLite files.
 */
class SqliteAgentStore(dbPath: String) : AgentStore {

    private val mapper: ObjectMapper = jacksonObjectMapper()
    private val db: Database

    init {
        db = Database.connect(
            url = "jdbc:sqlite:$dbPath",
            driver = "org.sqlite.JDBC",
        )
        transaction(db) {
            SchemaUtils.create(AgentDefinitions)
        }
        // PRAGMA foreign_keys=ON is intentionally omitted here.
        // SQLite requires PRAGMA foreign_keys to be set outside any transaction,
        // and exec() inside transaction() still creates an implicit txn.
        // Golem does not rely on FK enforcement at the SQLite level.
    }

    object AgentDefinitions : Table("agent_definitions") {
        val id = varchar("id", 128).uniqueIndex()
        val name = varchar("name", 256)
        val description = text("description").nullable()
        val definitionJson = text("definition_json")
        val enabled = bool("enabled").default(true)
        val tags = text("tags").nullable()
        val createdAt = varchar("created_at", 32)
        val updatedAt = varchar("updated_at", 32)
    }

    override suspend fun create(agent: AgentDefinition): AgentDefinition {
        validate(agent)
        transaction(db) {
            val existing = AgentDefinitions.selectAll().where { AgentDefinitions.id eq agent.id }.singleOrNull()
            if (existing != null) {
                throw IllegalStateException("Agent '${agent.id}' already exists")
            }
            AgentDefinitions.insert { row ->
                row[id] = agent.id
                row[name] = agent.name
                row[description] = agent.description.ifBlank { null }
                row[definitionJson] = mapper.writeValueAsString(agent)
                row[enabled] = agent.enabled
                row[tags] = agent.tags.joinToString(",").ifBlank { null }
                row[createdAt] = agent.createdAt
                row[updatedAt] = agent.updatedAt
            }
        }
        return agent
    }

    override suspend fun get(id: String): AgentDefinition? = transaction(db) {
        AgentDefinitions.selectAll().where { AgentDefinitions.id eq id }
            .singleOrNull()
            ?.let { rowToAgent(it) }
    }

    override suspend fun list(tag: String?): List<AgentDefinition> = transaction(db) {
        val query = if (tag != null) {
            AgentDefinitions.selectAll().where { AgentDefinitions.tags like "%$tag%" }
        } else {
            AgentDefinitions.selectAll()
        }
        query.orderBy(AgentDefinitions.name).map { rowToAgent(it) }
    }

    override suspend fun update(agent: AgentDefinition): AgentDefinition? = transaction(db) {
        validate(agent)
        val existing = AgentDefinitions.selectAll().where { AgentDefinitions.id eq agent.id }.singleOrNull()
            ?: return@transaction null

        AgentDefinitions.update({ AgentDefinitions.id eq agent.id }) {
            it[name] = agent.name
            it[description] = agent.description.ifBlank { null }
            it[definitionJson] = mapper.writeValueAsString(agent)
            it[enabled] = agent.enabled
            it[tags] = agent.tags.joinToString(",").ifBlank { null }
            it[updatedAt] = agent.updatedAt
        }
        agent
    }

    override suspend fun delete(id: String): Boolean = transaction(db) {
        val count = AgentDefinitions.deleteWhere { AgentDefinitions.id eq id }
        count > 0
    }

    override suspend fun count(): Int = transaction(db) {
        AgentDefinitions.selectAll().count().toInt()
    }

    override fun close() {
        // Exposed manages connection lifecycle
    }

    private fun rowToAgent(row: ResultRow): AgentDefinition {
        val json = row[AgentDefinitions.definitionJson]
        return mapper.readValue(json)
    }

    private fun validate(agent: AgentDefinition) {
        validateAgentDefinition(agent)
    }
}
