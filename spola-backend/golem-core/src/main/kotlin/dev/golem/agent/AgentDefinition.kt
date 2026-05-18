package dev.spola.agent

enum class ToolPolicy {
    /** Agent can use all tools in the global registry. */
    ALL,
    /** Agent can only use the tools listed in [toolsAllowed]. */
    LISTED,
    /** Agent has no tool access (chat only). */
    NONE,
}

/**
 * Expanded schema for a custom agent in Golem.
 *
 * Separates agent *identity* (system prompt, purpose, tools)
 * from runtime *model selection* (preferred + fallback providers/models).
 * Also covers permissions, memory scope, autonomy level, and output formats.
 */
data class AgentDefinition(
    // ── Identity ──────────────────────────────────────────
    val id: String,
    val name: String,
    val description: String = "",
    val version: Int = 1,

    // ── Persona / System prompt ──────────────────────────
    /** Full system prompt that defines the agent's behavior, tone, and expertise. */
    val systemPrompt: String,

    // ── Model Routing ────────────────────────────────────
    /** Primary model to use (e.g., "claude-sonnet-4", "gpt-4o"). */
    val preferredModel: String,
    /** Provider for the primary model (e.g., "openai", "anthropic", "openai-compat"). */
    val preferredProvider: String,

    /** Fallback model if the primary is unavailable. Null = no fallback. */
    val fallbackModel: String? = null,
    /** Provider for the fallback model. Null = same as preferredProvider. */
    val fallbackProvider: String? = null,

    // ── Model Parameters ─────────────────────────────────
    val temperature: Double? = null,
    val maxTokens: Int? = null,

    // ── Tool Configuration ──────────────────────────────
    /** How tool access is scoped for this agent. */
    val toolPolicy: ToolPolicy = ToolPolicy.ALL,
    /** Explicit tool allowlist used when [toolPolicy] is [ToolPolicy.LISTED]. */
    val toolsAllowed: List<String> = emptyList(),

    // ── Permission Scope ─────────────────────────────────
    /** Level of filesystem access: "read-write", "read-only", "none". */
    val filesystemAccess: String = "read-write",
    /** Whether shell commands are allowed at all. */
    val shellAccess: Boolean = true,
    /** Whether network/web tools are allowed. */
    val networkAccess: Boolean = true,

    // ── Autonomy ─────────────────────────────────────────
    /** When to ask for human approval: "auto", "ask_first", "never". */
    val executeCommands: String = "auto",
    /** Max turns before the agent loop stops. Null = use global default (25). */
    val maxTurnsOverride: Int? = null,

    // ── Memory Scope ─────────────────────────────────────
    /** Which memory pool this agent can access: "global", "agent", "none". */
    val memoryScope: String = "global",
    /** If memoryScope == "agent", this is the agent-specific namespace for memory keys. */
    val memoryNamespace: String? = null,

    // ── Output Format ───────────────────────────────────
    /** Response format hint: "text", "markdown", "json", "json_schema". */
    val responseFormat: String = "markdown",
    /** JSON schema for structured output (when responseFormat == "json_schema"). */
    val outputSchema: String? = null,

    // ── Budget ──────────────────────────────────────────
    /** Soft cost limit in USD. Null = no limit. */
    val maxCostUsd: Double? = null,
    /** Timeout in seconds for the entire agent run. Null = no limit. */
    val timeoutSeconds: Int? = null,

    // ── Lifecycle ───────────────────────────────────────
    val enabled: Boolean = true,
    val tags: List<String> = emptyList(),
    val createdAt: String = "",
    val updatedAt: String = "",
)

/**
 * Permission boundary built from an AgentDefinition.
 * Used at runtime to filter the tool registry and enforce access controls.
 */
data class AgentPermissions(
    val filesystemAccess: String,
    val shellAccess: Boolean,
    val networkAccess: Boolean,
    val executeCommands: String,
)

/** Builder to apply agent definition overrides to a tool registry. */
data class AgentRuntimeConfig(
    val maxTurns: Int,
    val temperature: Double?,
    val maxTokens: Int?,
    val memoryNamespace: String?,
)
