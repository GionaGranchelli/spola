package dev.spola

import dev.spola.agent.ProviderStore
import dev.spola.factory.ProviderResolver
import dev.spola.checkpoint.CheckpointManager
import dev.spola.checkpoint.CheckpointStore
import dev.spola.memory.MemoryStore
import dev.spola.memory.SqliteMemoryStore
import dev.spola.metrics.SpolaMetrics
import dev.spola.metrics.MetricsObserver
import dev.spola.persona.PersonaLoader
import dev.spola.tools.registerFileTools
import dev.spola.tools.registerShellTool
import dev.spola.tools.registerGitTools
import dev.spola.tools.registerWebTools
import dev.spola.tools.registerEditTool
import dev.spola.tools.registerDeliveryTools
import dev.spola.tools.registerTtsTool
import dev.tramai.core.provider.ModelProvider

/**
 * Configuration for architect mode — a two-phase execution pattern.
 *
 * Phase 1 ("Architect"): A cheaper/faster model researches and produces a plan.
 *   Destructive tools (write_file, edit_file, shell, git_commit, git_push) are stripped.
 *
 * Phase 2 ("Editor"): A more powerful model implements the plan.
 *   All tools are available, and the architect's plan is injected as additional context.
 */
data class ArchitectConfig(
    val architectModel: String = "gpt-4o-mini",
    val architectProvider: String = "openai",
    val editorModel: String = "gpt-4o",
    val editorProvider: String = "openai",
    val enabled: Boolean = false,
)

/**
 * The result of a complete architect-mode run.
 */
class ArchitectRunResult(
    val plan: String,
    val implementation: String,
)

/**
 * Two-phase agent runner that implements the Architect pattern.
 *
 * In Phase 1, a "cheap" model reasons about the problem and produces a plan
 * without being able to modify files. In Phase 2, a "powerful" model receives
 * the full plan as context and implements it with full tool access.
 *
 * `ArchitectRunner` creates two separate [SpolaAgent] instances — one per phase —
 * each with its own tool registry, provider, and model configuration.
 */
class ArchitectRunner(
    private val config: SpolaConfig,
    private val architectConfig: ArchitectConfig,
) {
    private val providerStore = ProviderStore.fromEnvironment()

    /**
     * Run the two-phase architect pattern.
     *
     * @param persona The system persona to use (same for both phases).
     * @param goal The user's objective.
     * @return The plan and implementation from both phases.
     */
    suspend fun run(persona: String, goal: String): ArchitectRunResult {
        // Phase 1: Architect builds a plan (no destructive tools)
        val plan = runArchitectPhase(persona, goal)

        // Phase 2: Editor implements the plan (all tools + plan as context)
        val implementation = runEditorPhase(persona, goal, plan)

        return ArchitectRunResult(
            plan = plan,
            implementation = implementation,
        )
    }

    /**
     * Run the architect phase with a restricted tool set.
     *
     * The architect can read files, search, use git_diff, and browse the web,
     * but cannot write files, edit files, execute shell commands, or commit/push to git.
     */
    private suspend fun runArchitectPhase(persona: String, goal: String): String {
        val toolRegistry = buildArchitectToolRegistry()

        val architectPersona = buildString {
            appendLine(persona.trim())
            appendLine()
            appendLine("# ARCHITECT MODE \u2014 PHASE 1: PLANNING")
            appendLine()
            appendLine(
                "You are in the **Architect Phase**. Your role is to analyze the request, " +
                    "research the codebase, and produce a detailed implementation plan."
            )
            appendLine()
            appendLine("## Constraints")
            appendLine("- You CAN read, search, and explore files to understand the codebase.")
            appendLine("- You CAN use git_diff to see current changes.")
            appendLine("- You CAN use web_search to look up documentation or APIs.")
            appendLine("- You CANNOT write or edit files.")
            appendLine("- You CANNOT execute shell commands.")
            appendLine("- You CANNOT commit or push to git repositories.")
            appendLine()
            appendLine("## Required Output")
            appendLine("After your research, produce a clear, step-by-step implementation plan that covers:")
            appendLine("1. **Files to create** \u2014 with path and purpose")
            appendLine("2. **Files to modify** \u2014 with path and what changes are needed")
            appendLine("3. **Implementation order** \u2014 dependencies between changes")
            appendLine("4. **Key design decisions** \u2014 alternatives considered and rationale")
            appendLine()
            appendLine(
                "Your plan will be passed verbatim to the Editor agent in Phase 2, " +
                    "who will implement it. Be thorough and specific."
            )
        }

        val (llmProvider, modelName) = resolveProvider(
            providerName = architectConfig.architectProvider,
            modelName = architectConfig.architectModel,
        )

        val agent = SpolaAgent(
            provider = llmProvider,
            effectiveModel = modelName,
            toolRegistry = toolRegistry,
            config = config,
        )

        return agent.run(persona = architectPersona, goal = goal)
    }

    /**
     * Run the editor phase with full tool access and the architect's plan as context.
     */
    private suspend fun runEditorPhase(persona: String, goal: String, plan: String): String {
        val toolRegistry = buildEditorToolRegistry()
        val memoryStore = SqliteMemoryStore(config.memoryDbPath)
        dev.spola.memory.registerMemoryTools(toolRegistry, memoryStore)

        val editorPersona = buildString {
            appendLine(persona.trim())
            appendLine()
            appendLine("# ARCHITECT MODE \u2014 PHASE 2: IMPLEMENTATION")
            appendLine()
            appendLine(
                "You are in the **Editor Phase**. You have received a detailed plan from the Architect. " +
                    "Your job is to implement it faithfully."
            )
            appendLine()
            appendLine("## The Architect's Plan")
            appendLine(plan)
            appendLine()
            appendLine("## Instructions")
            appendLine("- Implement the plan step by step.")
            appendLine("- You have full access to all tools: read, write, edit, shell, git.")
            appendLine("- If you discover issues with the plan, adapt but communicate your changes.")
            appendLine("- When done, provide a summary of what was implemented.")
        }

        val (llmProvider, modelName) = resolveProvider(
            providerName = architectConfig.editorProvider,
            modelName = architectConfig.editorModel,
        )

        val agent = SpolaAgent(
            provider = llmProvider,
            effectiveModel = modelName,
            toolRegistry = toolRegistry,
            config = config,
        )

        return agent.run(persona = editorPersona, goal = goal)
    }

    /**
     * Build a restricted tool registry for the architect phase.
     *
     * Only read-only and research tools are registered:
     * - read_file, search_files (read-only exploration)
     * - git_diff, git_status, git_log (read-only git inspection)
     * - web_search, web_fetch (web research)
     *
     * Destructive tools are intentionally excluded:
     * - write_file, edit_file, shell, git_commit, git_push
     */
    private fun buildArchitectToolRegistry(): ToolRegistry {
        val reg = ToolRegistry()
        // Register only read/search tools
        registerFileTools(reg)
        registerGitTools(reg)
        registerWebTools(reg)

        // Remove destructive tools from the registry
        reg.unregister("write_file")
        reg.unregister("edit_file")
        reg.unregister("shell")
        reg.unregister("git_commit")
        reg.unregister("git_push")

        return reg
    }

    /**
     * Build a full tool registry for the editor phase.
     * All standard tools are available: read, write, edit, shell, git, web.
     */
    private fun buildEditorToolRegistry(): ToolRegistry {
        val reg = ToolRegistry()
        registerFileTools(reg)
        registerShellTool(reg)
        registerGitTools(reg)
        registerWebTools(reg)
        registerEditTool(reg)
        registerDeliveryTools(reg, config)
        registerTtsTool(reg, config)
        return reg
    }

    /**
     * Resolve an LLM provider by name, reading credentials from environment variables.
     */
    private fun resolveProvider(providerName: String, modelName: String): Pair<ModelProvider, String> {
        val providerConfig = providerStore.get(providerName)
        return ProviderResolver.resolveNamed(providerConfig, modelName)
    }
}
