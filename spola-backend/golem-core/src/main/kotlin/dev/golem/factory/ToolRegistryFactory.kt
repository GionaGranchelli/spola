package dev.spola.factory

import dev.spola.GolemConfig
import dev.spola.ToolRegistry
import dev.spola.agent.AgentDefinition
import dev.spola.agent.AgentTools
import dev.spola.agent.PermissionEnforcer
import dev.spola.agent.SqliteAgentStore
import dev.spola.agent.ToolPolicy
import dev.spola.checkpoint.CheckpointManager
import dev.spola.checkpoint.CheckpointStore
import dev.spola.checkpoint.registerCheckpointTools
import dev.spola.jvm.JvmIndexCoordinator
import dev.spola.jvm.ProjectInsightStore
import dev.spola.jvm.SqliteJvmProjectIndex
import dev.spola.kanban.KanbanStore
import dev.spola.kanban.SqliteKanbanStore
import dev.spola.kanban.registerKanbanTools
import dev.spola.memory.MemoryStore
import dev.spola.memory.NoopMemoryStore
import dev.spola.memory.registerMemoryTools
import dev.spola.metrics.GolemMetrics
import dev.spola.plugin.PluginLoader
import dev.spola.scheduler.GolemJobStore
import dev.spola.scheduler.SqliteGolemJobStore
import dev.spola.scheduler.registerSchedulerTools
import dev.spola.skill.SkillCatalog
import dev.spola.skill.SkillCreateTools
import dev.spola.skill.SkillTools
import dev.spola.tools.registerDeliveryTools
import dev.spola.tools.registerJvmTools
import dev.spola.tools.registerProjectInsightTools
import dev.spola.tools.registerProvenanceTools
import dev.spola.tools.registerTools
import dev.spola.workflow.WorkflowExecutionService
import dev.spola.workflow.WorkflowCreateTools
import dev.spola.workflow.registerWorkflowTools
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Single source of truth for building [ToolRegistry] instances.
 *
 * Eliminates registry drift between [GolemFactory.create], [GolemApiServer][dev.spola.api.GolemApiServer],
 * and [McpRunner][dev.spola.mcp.McpRunner].
 */
object ToolRegistryFactory {

    /**
     * Build the default tool registry used for agent creation.
     */
    suspend fun buildDefaultToolRegistry(
        config: GolemConfig,
        memoryStore: MemoryStore,
        schedulerStore: GolemJobStore?,
        checkpointManager: CheckpointManager,
        jvmIndex: SqliteJvmProjectIndex,
        jvmIndexCoordinator: JvmIndexCoordinator,
        golemMetrics: GolemMetrics,
        model: String,
        workflowExecutionService: WorkflowExecutionService? = null,
    ): ToolRegistry = ToolRegistry().apply {
        registerTools(this, config)
        registerMemoryTools(this, memoryStore)
        if (schedulerStore != null) {
            registerSchedulerTools(this, schedulerStore)
        }
        registerCheckpointTools(this, checkpointManager)
        PluginLoader.loadPlugins(this, config)
        jvmIndexCoordinator.startWatching(Paths.get(config.workingDirectory).toAbsolutePath().normalize())
        registerJvmTools(this, jvmIndex, jvmIndexCoordinator)
        registerProjectInsightTools(this, ProjectInsightStore("${config.jvmIndexDbPath}.insights"))
        AgentTools.register(this, SqliteAgentStore(config.agentsDbPath))
        SkillTools.register(this, skillsDir = Path.of(config.skillsDir), config = config, toolRegistry = this)
        SkillCreateTools.register(this, skillsDir = Path.of(config.skillsDir), catalog = SkillCatalog(Path.of(config.skillsDir), null))
        registerDeliveryTools(this, config)
        registerProvenanceTools(this, checkpointManager, golemMetrics, model = model)
        if (workflowExecutionService != null) {
            registerWorkflowTools(this, workflowExecutionService)
            WorkflowCreateTools.register(this, config, workflowExecutionService.workflowRegistry)
        }
    }

    /**
     * Build a tool registry for an agent defined by [AgentDefinition] with permission scoping.
     */
    suspend fun buildAgentToolRegistry(
        config: GolemConfig,
        permissionEnforcer: PermissionEnforcer,
        memoryStore: MemoryStore,
        agentDef: AgentDefinition,
        model: String = config.model,
        workflowExecutionService: WorkflowExecutionService? = null,
    ): ToolRegistry = ToolRegistry().apply {
        registerTools(this, config, permissionEnforcer)

        val effectiveMemoryStore = if (agentDef.memoryScope == "none") {
            NoopMemoryStore()
        } else {
            memoryStore
        }
        val effectiveNamespace = agentDef.memoryNamespace ?: if (agentDef.memoryScope == "agent") agentDef.id else null
        registerMemoryTools(this, effectiveMemoryStore, effectiveNamespace)

        val schedulerStore = config.schedulerDbPath
            .takeIf { it.isNotBlank() }
            ?.let(::SqliteGolemJobStore)
        if (schedulerStore != null && agentDef.memoryScope != "none") {
            registerSchedulerTools(this, schedulerStore)
        }
        val jvmIndex = SqliteJvmProjectIndex(config.jvmIndexDbPath)
        val jvmIndexCoordinator = JvmIndexCoordinator(autoRefresh = config.jvmIndexAutoRefresh) {
            config.workingDirectory
        }
        jvmIndexCoordinator.startWatching(Paths.get(config.workingDirectory).toAbsolutePath().normalize())
        registerJvmTools(this, jvmIndex, jvmIndexCoordinator)
        registerProjectInsightTools(this, ProjectInsightStore("${config.jvmIndexDbPath}.insights"))
        val checkpointStore = CheckpointStore(config.checkpointDbPath)
        val checkpointManager = CheckpointManager(checkpointStore)
        registerCheckpointTools(this, checkpointManager)
        val golemMetrics = GolemMetrics(isEnabled = config.metricsEnabled)
        registerProvenanceTools(this, checkpointManager, golemMetrics, model = model)
        AgentTools.register(this, SqliteAgentStore(config.agentsDbPath))
        SkillTools.register(this, skillsDir = Path.of(config.skillsDir), config = config, toolRegistry = this)
        SkillCreateTools.register(this, skillsDir = Path.of(config.skillsDir), catalog = SkillCatalog(Path.of(config.skillsDir), null))
        registerDeliveryTools(this, config)
        if (workflowExecutionService != null) {
            registerWorkflowTools(this, workflowExecutionService)
        }

        // Apply permission scoping after all agent-visible tools are registered
        applyPermissionScoping(this, agentDef)
    }

    /**
     * Build a tool registry for the API server.
     */
    suspend fun buildApiToolRegistry(
        config: GolemConfig,
        memoryStore: MemoryStore,
        jobStore: GolemJobStore,
        kanbanStore: KanbanStore = SqliteKanbanStore(config.kanbanDbPath),
        checkpointManager: CheckpointManager,
        model: String = config.model,
        workflowExecutionService: WorkflowExecutionService? = null,
    ): ToolRegistry = ToolRegistry().apply {
        registerTools(this, config)
        registerMemoryTools(this, memoryStore)
        registerSchedulerTools(this, jobStore)
        registerKanbanTools(this, kanbanStore)
        registerCheckpointTools(this, checkpointManager)
        registerJvmTools(this, SqliteJvmProjectIndex(config.jvmIndexDbPath), JvmIndexCoordinator(autoRefresh = config.jvmIndexAutoRefresh) { config.workingDirectory })
        registerProjectInsightTools(this, ProjectInsightStore("${config.jvmIndexDbPath}.insights"))
        AgentTools.register(this, SqliteAgentStore(config.agentsDbPath))
        SkillTools.register(this, skillsDir = Path.of(config.skillsDir), config = config, toolRegistry = this)
        SkillCreateTools.register(this, skillsDir = Path.of(config.skillsDir), catalog = SkillCatalog(Path.of(config.skillsDir), null))
        registerDeliveryTools(this, config)
        registerProvenanceTools(this, checkpointManager, model = model)
        if (workflowExecutionService != null) {
            registerWorkflowTools(this, workflowExecutionService)
        }
    }

    /**
     * Build a tool registry for the MCP server.
     */
    suspend fun buildMcpToolRegistry(
        config: GolemConfig,
        memoryStore: MemoryStore,
        schedulerStore: GolemJobStore?,
        checkpointManager: CheckpointManager,
        coordinator: JvmIndexCoordinator,
        model: String = config.model,
        workflowExecutionService: WorkflowExecutionService? = null,
    ): ToolRegistry = ToolRegistry().apply {
        registerTools(this)
        registerMemoryTools(this, memoryStore)
        if (schedulerStore != null) {
            registerSchedulerTools(this, schedulerStore)
        }
        val kanbanStore = SqliteKanbanStore(config.kanbanDbPath)
        registerKanbanTools(this, kanbanStore)
        registerCheckpointTools(this, checkpointManager)
        registerJvmTools(this, SqliteJvmProjectIndex(config.jvmIndexDbPath), coordinator)
        registerProjectInsightTools(this, ProjectInsightStore("${config.jvmIndexDbPath}.insights"))
        registerProvenanceTools(this, checkpointManager, model = model)
        AgentTools.register(this, SqliteAgentStore(config.agentsDbPath))
        SkillTools.register(this, skillsDir = Path.of(config.skillsDir), config = config, toolRegistry = this)
        if (workflowExecutionService != null) {
            registerWorkflowTools(this, workflowExecutionService)
        }
    }

    /**
     * Filter the tool registry based on the agent's permission settings.
     */
    private fun applyPermissionScoping(registry: ToolRegistry, agentDef: AgentDefinition) {
        when (agentDef.toolPolicy) {
            ToolPolicy.NONE -> {
                registry.list().forEach { registry.unregister(it.name) }
                return
            }
            ToolPolicy.LISTED -> {
                val allowed = agentDef.toolsAllowed.toSet()
                registry.list()
                    .filter { it.name !in allowed }
                    .forEach { registry.unregister(it.name) }
            }
            ToolPolicy.ALL -> {}
        }

        val removeNames = mutableListOf<String>()

        for (tool in registry.list()) {
            val name = tool.name.lowercase()

            // Filesystem tools
            if (agentDef.filesystemAccess == "none" && (name.contains("file") || name.contains("edit"))) {
                removeNames.add(tool.name)
            } else if (agentDef.filesystemAccess == "read-only" &&
                (name.contains("write_file") || name.contains("edit_file"))
            ) {
                removeNames.add(tool.name)
            }

            // Shell tools
            if (!agentDef.shellAccess && (name.contains("shell") || name.contains("terminal"))) {
                removeNames.add(tool.name)
            }

            // Network tools
            if (!agentDef.networkAccess && (name.contains("web_") || name.contains("http"))) {
                removeNames.add(tool.name)
            }
        }

        for (name in removeNames.distinct()) {
            registry.unregister(name)
        }
    }
}
