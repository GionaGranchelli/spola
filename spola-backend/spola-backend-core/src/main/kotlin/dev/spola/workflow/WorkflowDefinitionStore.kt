package dev.spola.workflow

/**
 * Simple in-memory store for workflow definitions (name + description pairs).
 * These are user-created shortcuts that reference the actual workflow templates
 * by name. Definitions are not persisted across restarts in this version.
 */
class WorkflowDefinitionStore {
    private data class Definition(
        val id: String,
        val name: String,
        val description: String,
        val enabled: Boolean = true,
    )

    private val definitions = mutableMapOf<String, Definition>()
    private var counter = 0

    fun create(name: String, description: String): Map<String, Any> {
        val id = "def-${++counter}"
        definitions[id] = Definition(id, name, description, enabled = true)
        return toMap(definitions[id]!!)
    }

    fun list(): List<Map<String, Any>> = definitions.values.map(::toMap)

    fun get(id: String): Map<String, Any>? = definitions[id]?.let(::toMap)

    fun update(id: String, name: String?, description: String?): Map<String, Any>? {
        val def = definitions[id] ?: return null
        val updated = def.copy(
            name = name ?: def.name,
            description = description ?: def.description,
        )
        definitions[id] = updated
        return toMap(updated)
    }

    fun delete(id: String): Boolean = definitions.remove(id) != null

    fun toggle(id: String, enabled: Boolean): Map<String, Any>? {
        val def = definitions[id] ?: return null
        val updated = def.copy(enabled = enabled)
        definitions[id] = updated
        return toMap(updated)
    }

    private fun toMap(def: Definition): Map<String, Any> = mapOf(
        "id" to def.id,
        "name" to def.name,
        "description" to def.description,
        "enabled" to def.enabled,
    )
}
