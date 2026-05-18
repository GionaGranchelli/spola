package dev.spola.agent

/**
 * Persistence interface for AgentDefinition records.
 */
interface AgentStore : AutoCloseable {

    /** Store a new agent definition. Throws if id already exists. */
    suspend fun create(agent: AgentDefinition): AgentDefinition

    /** Retrieve an agent by id. Returns null if not found. */
    suspend fun get(id: String): AgentDefinition?

    /** List all stored agent definitions, optionally filtered by tag. */
    suspend fun list(tag: String? = null): List<AgentDefinition>

    /** Update an existing agent definition. Returns null if not found. */
    suspend fun update(agent: AgentDefinition): AgentDefinition?

    /** Remove an agent definition by id. Returns true if deleted. */
    suspend fun delete(id: String): Boolean

    /** Count stored agent definitions. */
    suspend fun count(): Int
}

fun validateAgentDefinition(agent: AgentDefinition) {
    require(agent.id.isNotBlank()) { "Agent id must not be blank" }
    require(agent.name.isNotBlank()) { "Agent name must not be blank" }
    require(agent.systemPrompt.isNotBlank()) { "Agent system prompt must not be blank" }
    require(agent.preferredModel.isNotBlank()) { "Agent preferred model must not be blank" }
    require(agent.filesystemAccess in listOf("read-write", "read-only", "none")) {
        "filesystemAccess must be 'read-write', 'read-only', or 'none'"
    }
    require(agent.executeCommands in listOf("auto", "ask_first", "never")) {
        "executeCommands must be 'auto', 'ask_first', or 'never'"
    }
    require(agent.memoryScope in listOf("global", "agent", "none")) {
        "memoryScope must be 'global', 'agent', or 'none'"
    }
    if (!agent.shellAccess) {
        require(agent.executeCommands != "auto") {
            "shellAccess=false and executeCommands=auto is contradictory"
        }
    }
    if (agent.memoryScope == "none") {
        require(agent.memoryNamespace == null) {
            "memoryScope=none but memoryNamespace is set"
        }
    }
    if (agent.toolPolicy == ToolPolicy.LISTED) {
        require(agent.toolsAllowed.isNotEmpty()) {
            "toolsAllowed must not be empty when toolPolicy=LISTED"
        }
    }
    if (agent.toolPolicy != ToolPolicy.LISTED) {
        require(agent.toolsAllowed.isEmpty()) {
            "toolsAllowed must be empty unless toolPolicy=LISTED"
        }
    }
}
