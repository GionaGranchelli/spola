package dev.spola.agent

import dev.spola.Tool
import dev.spola.ToolParameter
import dev.spola.ToolParameterType
import dev.spola.ToolRegistry
import dev.spola.ToolResult
import java.time.Instant

/**
 * Registers LLM-accessible tools for managing custom agent definitions.
 *
 * These tools let the agent itself (or the user via the agent) create,
 * list, inspect, update, delete, and run custom agents.
 */
object AgentTools {
    private fun parseToolPolicy(raw: String?, default: ToolPolicy): ToolPolicy {
        if (raw == null) return default
        return try {
            ToolPolicy.valueOf(raw.uppercase())
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid tool_policy: $raw")
        }
    }

    /**
     * Register agent management tools into [registry] backed by [store].
     */
    suspend fun register(registry: ToolRegistry, store: AgentStore) {
        registry.register(agentCreateTool(store))
        registry.register(agentListTool(store))
        registry.register(agentGetTool(store))
        registry.register(agentUpdateTool(store))
        registry.register(agentDeleteTool(store))
        registry.register(agentRunTool(store))
    }

    private fun agentCreateTool(store: AgentStore) = Tool(
        name = "agent_create",
        description = "Create a new custom agent definition with provider, model, system prompt, tools, and permissions. Returns the created agent id.",
        parameters = listOf(
            ToolParameter("id", "Unique identifier for this agent (e.g., 'security-reviewer')", ToolParameterType.STRING),
            ToolParameter("name", "Human-readable name for this agent", ToolParameterType.STRING),
            ToolParameter("description", "Short description of what this agent does", ToolParameterType.STRING, required = false),
            ToolParameter("system_prompt", "Full system prompt defining this agent's behavior, tone, and expertise", ToolParameterType.STRING),
            ToolParameter("preferred_model", "Primary model to use (e.g., 'claude-sonnet-4', 'gpt-4o')", ToolParameterType.STRING),
            ToolParameter("preferred_provider", "Provider for the primary model (e.g., 'openai', 'anthropic', 'openai-compat')", ToolParameterType.STRING),
            ToolParameter("fallback_model", "Fallback model if primary is unavailable", ToolParameterType.STRING, required = false),
            ToolParameter("temperature", "Model temperature (0.0 - 2.0)", ToolParameterType.STRING, required = false),
            ToolParameter("max_tokens", "Maximum output tokens", ToolParameterType.INTEGER, required = false),
            ToolParameter("tool_policy", "Tool access policy: 'ALL', 'LISTED', 'NONE'", ToolParameterType.STRING, required = false),
            ToolParameter("tools_allowed", "Comma-separated tool names allowed when tool_policy=LISTED", ToolParameterType.STRING, required = false),
            ToolParameter("filesystem_access", "Filesystem access level: 'read-write', 'read-only', 'none'", ToolParameterType.STRING, required = false),
            ToolParameter("shell_access", "Whether shell commands are allowed (true/false)", ToolParameterType.STRING, required = false),
            ToolParameter("network_access", "Whether network/web tools are allowed (true/false)", ToolParameterType.STRING, required = false),
            ToolParameter("execute_commands", "Command execution mode: 'auto', 'ask_first', 'never'", ToolParameterType.STRING, required = false),
            ToolParameter("memory_scope", "Memory scope: 'global', 'agent', 'none'", ToolParameterType.STRING, required = false),
            ToolParameter("tags", "Comma-separated tags for organization", ToolParameterType.STRING, required = false),
        ),
        execute = { args ->
            val id = args["id"] as? String ?: return@Tool ToolResult.fail("id is required")
            val name = args["name"] as? String ?: return@Tool ToolResult.fail("name is required")
            val systemPrompt = args["system_prompt"] as? String ?: return@Tool ToolResult.fail("system_prompt is required")
            val model = args["preferred_model"] as? String ?: return@Tool ToolResult.fail("preferred_model is required")
            val provider = args["preferred_provider"] as? String ?: return@Tool ToolResult.fail("preferred_provider is required")

            try {
                val agent = AgentDefinition(
                    id = id,
                    name = name,
                    description = args["description"] as? String ?: "",
                    systemPrompt = systemPrompt,
                    preferredModel = model,
                    preferredProvider = provider,
                    fallbackModel = args["fallback_model"] as? String,
                    temperature = (args["temperature"] as? String)?.toDoubleOrNull(),
                    maxTokens = args["max_tokens"] as? Int,
                    toolPolicy = parseToolPolicy(args["tool_policy"] as? String, ToolPolicy.ALL),
                    toolsAllowed = (args["tools_allowed"] as? String)?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
                    filesystemAccess = (args["filesystem_access"] as? String) ?: "read-write",
                    shellAccess = (args["shell_access"] as? String)?.toBoolean() ?: true,
                    networkAccess = (args["network_access"] as? String)?.toBoolean() ?: true,
                    executeCommands = (args["execute_commands"] as? String) ?: "auto",
                    memoryScope = (args["memory_scope"] as? String) ?: "global",
                    tags = (args["tags"] as? String)?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
                    createdAt = Instant.now().toString(),
                    updatedAt = Instant.now().toString(),
                )
                validateAgentDefinition(agent)
                val created = store.create(agent)
                ToolResult.ok("Created agent '${created.id}' (${created.name})")
            } catch (e: Exception) {
                ToolResult.fail("Failed to create agent: ${e.message}")
            }
        },
    )

    private fun agentListTool(store: AgentStore) = Tool(
        name = "agent_list",
        description = "List all available custom agent definitions. Optionally filter by tag.",
        parameters = listOf(
            ToolParameter("tag", "Optional tag to filter by", ToolParameterType.STRING, required = false),
        ),
        execute = { args ->
            val tag = args["tag"] as? String
            val agents = store.list(tag)

            if (agents.isEmpty()) {
                ToolResult.ok("No custom agents defined. Use agent_create to add one.")
            } else {
                val lines = agents.map { a ->
                    val status = if (a.enabled) "✅" else "⛔"
                    "$status ${a.id} — ${a.name} (${a.preferredProvider}/${a.preferredModel})"
                }
                ToolResult.ok("Custom Agents (${agents.size}):\n" + lines.joinToString("\n"))
            }
        },
    )

    private fun agentGetTool(store: AgentStore) = Tool(
        name = "agent_get",
        description = "Get the full definition of a custom agent by id.",
        parameters = listOf(
            ToolParameter("id", "Agent id to retrieve", ToolParameterType.STRING),
        ),
        execute = { args ->
            val id = args["id"] as? String ?: return@Tool ToolResult.fail("id is required")
            val agent = store.get(id) ?: return@Tool ToolResult.fail("Agent not found: $id")

            val lines = buildString {
                appendLine("Agent: ${agent.id}")
                appendLine("Name: ${agent.name}")
                appendLine("Description: ${agent.description}")
                appendLine("---")
                appendLine("Preferred: ${agent.preferredProvider}/${agent.preferredModel}")
                agent.fallbackModel?.let { appendLine("Fallback: ${agent.fallbackProvider ?: agent.preferredProvider}/$it") }
                agent.temperature?.let { appendLine("Temperature: $it") }
                agent.maxTokens?.let { appendLine("Max Tokens: $it") }
                appendLine("---")
                appendLine("Filesystem: ${agent.filesystemAccess}")
                appendLine("Shell: ${agent.shellAccess}")
                appendLine("Network: ${agent.networkAccess}")
                appendLine("Execute: ${agent.executeCommands}")
                appendLine("Tool Policy: ${agent.toolPolicy}")
                if (agent.toolPolicy == ToolPolicy.LISTED) {
                    appendLine("Allowed Tools: ${agent.toolsAllowed.joinToString(", ")}")
                }
                appendLine("Memory Scope: ${agent.memoryScope}")
                appendLine("---")
                appendLine("Response Format: ${agent.responseFormat}")
                appendLine("Tags: ${agent.tags.joinToString(", ")}")
                appendLine("Version: ${agent.version} | Enabled: ${agent.enabled}")
                appendLine("Created: ${agent.createdAt}")
                appendLine("Updated: ${agent.updatedAt}")
                appendLine("---")
                appendLine("Instructions:")
                appendLine(agent.systemPrompt)
            }

            ToolResult.ok(lines.trimEnd())
        },
    )

    private fun agentUpdateTool(store: AgentStore) = Tool(
        name = "agent_update",
        description = "Update an existing custom agent definition. Only provided fields are updated.",
        parameters = listOf(
            ToolParameter("id", "Agent id to update", ToolParameterType.STRING),
            ToolParameter("name", "New name", ToolParameterType.STRING, required = false),
            ToolParameter("description", "New description", ToolParameterType.STRING, required = false),
            ToolParameter("system_prompt", "New system prompt", ToolParameterType.STRING, required = false),
            ToolParameter("preferred_model", "New primary model", ToolParameterType.STRING, required = false),
            ToolParameter("preferred_provider", "New provider", ToolParameterType.STRING, required = false),
            ToolParameter("tool_policy", "Tool access policy", ToolParameterType.STRING, required = false),
            ToolParameter("tools_allowed", "Comma-separated allowed tools", ToolParameterType.STRING, required = false),
            ToolParameter("enabled", "Whether this agent is enabled (true/false)", ToolParameterType.STRING, required = false),
            ToolParameter("filesystem_access", "Filesystem access level", ToolParameterType.STRING, required = false),
            ToolParameter("shell_access", "Whether shell commands are allowed", ToolParameterType.STRING, required = false),
            ToolParameter("memory_scope", "Memory scope", ToolParameterType.STRING, required = false),
        ),
        execute = { args ->
            val id = args["id"] as? String ?: return@Tool ToolResult.fail("id is required")
            val existing = store.get(id) ?: return@Tool ToolResult.fail("Agent not found: $id")
            val updatedToolPolicy = parseToolPolicy(args["tool_policy"] as? String, existing.toolPolicy)
            val updatedMemoryScope = args["memory_scope"] as? String ?: existing.memoryScope

            val updated = existing.copy(
                name = args["name"] as? String ?: existing.name,
                description = args["description"] as? String ?: existing.description,
                systemPrompt = args["system_prompt"] as? String ?: existing.systemPrompt,
                preferredModel = args["preferred_model"] as? String ?: existing.preferredModel,
                preferredProvider = args["preferred_provider"] as? String ?: existing.preferredProvider,
                toolPolicy = updatedToolPolicy,
                toolsAllowed = when {
                    args["tools_allowed"] != null -> (args["tools_allowed"] as? String)?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
                    updatedToolPolicy != ToolPolicy.LISTED -> emptyList()
                    else -> existing.toolsAllowed
                },
                filesystemAccess = args["filesystem_access"] as? String ?: existing.filesystemAccess,
                shellAccess = (args["shell_access"] as? String)?.toBoolean() ?: existing.shellAccess,
                memoryScope = updatedMemoryScope,
                memoryNamespace = when (updatedMemoryScope) {
                    "agent" -> existing.memoryNamespace ?: existing.id
                    "none" -> null
                    else -> null
                },
                enabled = (args["enabled"] as? String)?.toBoolean() ?: existing.enabled,
                version = existing.version + 1,
                updatedAt = Instant.now().toString(),
            )

            return@Tool try {
                validateAgentDefinition(updated)
                val result = store.update(updated)
                if (result != null) {
                    ToolResult.ok("Updated agent '$id' (v${updated.version})")
                } else {
                    ToolResult.fail("Agent '$id' not found during update")
                }
            } catch (e: Exception) {
                ToolResult.fail("Failed to update agent: ${e.message}")
            }
        },
    )

    private fun agentDeleteTool(store: AgentStore) = Tool(
        name = "agent_delete",
        description = "Delete a custom agent definition by id.",
        parameters = listOf(
            ToolParameter("id", "Agent id to delete", ToolParameterType.STRING),
        ),
        execute = { args ->
            val id = args["id"] as? String ?: return@Tool ToolResult.fail("id is required")
            if (store.delete(id)) {
                ToolResult.ok("Deleted agent '$id'")
            } else {
                ToolResult.fail("Agent not found: $id")
            }
        },
    )

    private fun agentRunTool(store: AgentStore) = Tool(
        name = "agent_run",
        description = "Run a custom agent by id with a specific goal. The agent uses its defined provider, model, persona, and permissions.",
        parameters = listOf(
            ToolParameter("agent_id", "Id of the custom agent to run", ToolParameterType.STRING),
            ToolParameter("goal", "The goal or instruction to give this agent", ToolParameterType.STRING),
        ),
        execute = { _ ->
            ToolResult.fail("Tool 'agent_run' requires the Golem API server — run 'golem --api --api-key <key>' first, then use the API server's POST /api/agents/run endpoint")
        },
    )
}
