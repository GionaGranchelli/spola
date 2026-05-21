package dev.spola.factory

import dev.spola.SpolaConfig
import dev.spola.ToolRegistry
import dev.spola.agent.AgentDefinition
import dev.spola.agent.AgentTools
import dev.spola.agent.PermissionEnforcer
import dev.spola.agent.SqliteAgentStore
import dev.spola.agent.ToolPolicy
import dev.spola.checkpoint.CheckpointManager
import dev.spola.checkpoint.registerCheckpointTools
import dev.spola.jvm.JvmIndexCoordinator
import dev.spola.jvm.ProjectInsightStore
import dev.spola.jvm.SqliteJvmProjectIndex
import dev.spola.kanban.KanbanStore
import dev.spola.kanban.SqliteKanbanStore
import dev.spola.kanban.registerKanbanTools
import dev.spola.memory.MemoryStore
import dev.spola.memory.NoopMemoryStore
import dev.spola.memory.SqliteMemoryStore
import dev.spola.memory.registerMemoryTools
import dev.spola.metrics.SpolaMetrics
import dev.spola.plugin.PluginLoader
import dev.spola.scheduler.SpolaJobStore
import dev.spola.scheduler.SqliteSpolaJobStore
import dev.spola.scheduler.registerSchedulerTools
import dev.spola.skill.SkillCatalog
import dev.spola.skill.SkillCreateTools
import dev.spola.skill.SkillTools
import dev.spola.tools.registerDeliveryTools
import dev.spola.tools.registerJvmTools
import dev.spola.tools.registerProjectInsightTools
import dev.spola.tools.registerProvenanceTools
import dev.spola.tools.registerTools
import dev.spola.workflow.WorkflowCreateTools
import dev.spola.workflow.WorkflowExecutionService
import dev.spola.workflow.registerWorkflowTools
import java.nio.file.Path
import java.nio.file.Paths

object ToolRegistryFactory {

    @Suppress("LongParameterList")
    suspend fun buildToolRegistry(
        config: SpolaConfig,
        includeKanban: Boolean = false,
        includeWorkflowCreate: Boolean = false,
        includeScheduler: Boolean = false,
        includeConfigTools: Boolean = false,
        includePlugins: Boolean = false,
        includeAgentSpecific: Boolean = false,
        includeReadOnly: Boolean = false,
        permissionEnforcer: PermissionEnforcer? = null,
        checkpointManager: CheckpointManager? = null,
        memoryStore: MemoryStore? = null,
        schedulerStore: SpolaJobStore? = null,
        kanbanStore: KanbanStore? = null,
        coordinator: JvmIndexCoordinator? = null,
        workflowExecutionService: WorkflowExecutionService? = null,
        model: String = config.provider.defaultModel,
        spolaMetrics: SpolaMetrics? = null,
        agentDef: AgentDefinition? = null,
    ): ToolRegistry {
        val effectiveMemoryStore = when {
            agentDef?.memoryScope == "none" -> NoopMemoryStore()
            memoryStore != null -> memoryStore
            else -> SqliteMemoryStore(config.database.memoryDbPath)
        }
        val effectiveNamespace = agentDef?.memoryNamespace
            ?: if (agentDef?.memoryScope == "agent") agentDef.id else null
        val effectiveSchedulerStore = schedulerStore ?: if (includeScheduler) {
            config.database.schedulerDbPath.takeIf { it.isNotBlank() }?.let(::SqliteSpolaJobStore)
        } else {
            null
        }
        val effectiveCheckpointManager = checkpointManager ?: CheckpointManager.fromConfig(config)
        val effectiveCoordinator = coordinator ?: JvmIndexCoordinator(autoRefresh = config.jvmIndexAutoRefresh) {
            config.workingDirectory
        }

        return ToolRegistry().apply {
            registerTools(this, config, permissionEnforcer)
            registerMemoryTools(this, effectiveMemoryStore, effectiveNamespace)
            if (includeScheduler && effectiveSchedulerStore != null && agentDef?.memoryScope != "none") {
                registerSchedulerTools(this, effectiveSchedulerStore)
            }
            if (includeKanban) {
                registerKanbanTools(
                    this,
                    kanbanStore ?: SqliteKanbanStore(config.database.kanbanDbPath),
                )
            }
            registerCheckpointTools(this, effectiveCheckpointManager)
            if (includePlugins) {
                PluginLoader.loadPlugins(this, config)
            }
            effectiveCoordinator.startWatching(Paths.get(config.workingDirectory).toAbsolutePath().normalize())
            registerJvmTools(this, SqliteJvmProjectIndex(config.database.jvmIndexDbPath), effectiveCoordinator)
            registerProjectInsightTools(this, ProjectInsightStore("${config.database.jvmIndexDbPath}.insights"))
            registerDeliveryTools(this, config)
            registerProvenanceTools(
                this,
                effectiveCheckpointManager,
                spolaMetrics ?: SpolaMetrics(isEnabled = config.metrics.metricsEnabled),
                model = model,
            )
            AgentTools.register(this, SqliteAgentStore(config.database.agentsDbPath))
            SkillTools.register(this, skillsDir = Path.of(config.skillsDir), config = config, toolRegistry = this)
            SkillCreateTools.register(this, skillsDir = Path.of(config.skillsDir), catalog = SkillCatalog(Path.of(config.skillsDir), null))
            if (workflowExecutionService != null) {
                registerWorkflowTools(this, workflowExecutionService)
                if (includeWorkflowCreate) {
                    WorkflowCreateTools.register(this, config, workflowExecutionService.workflowRegistry)
                }
            }
            if (includeConfigTools) {
                Unit
            }
            if (includeAgentSpecific) {
                applyPermissionScoping(this, agentDef)
            }
            if (includeReadOnly) {
                Unit
            }
        }
    }

    suspend fun buildDefaultToolRegistry(
        config: SpolaConfig,
        memoryStore: MemoryStore,
        schedulerStore: SpolaJobStore?,
        checkpointManager: CheckpointManager,
        jvmIndex: SqliteJvmProjectIndex,
        jvmIndexCoordinator: JvmIndexCoordinator,
        spolaMetrics: SpolaMetrics,
        model: String,
        workflowExecutionService: WorkflowExecutionService? = null,
    ): ToolRegistry = buildToolRegistry(
        config = config,
        includeScheduler = schedulerStore != null,
        includeWorkflowCreate = workflowExecutionService != null,
        includePlugins = true,
        checkpointManager = checkpointManager,
        memoryStore = memoryStore,
        schedulerStore = schedulerStore,
        coordinator = jvmIndexCoordinator,
        workflowExecutionService = workflowExecutionService,
        model = model,
        spolaMetrics = spolaMetrics,
    )

    suspend fun buildAgentToolRegistry(
        config: SpolaConfig,
        permissionEnforcer: PermissionEnforcer,
        memoryStore: MemoryStore,
        agentDef: AgentDefinition,
        model: String = config.provider.defaultModel,
        workflowExecutionService: WorkflowExecutionService? = null,
    ): ToolRegistry = buildToolRegistry(
        config = config,
        includeScheduler = true,
        includeAgentSpecific = true,
        permissionEnforcer = permissionEnforcer,
        memoryStore = memoryStore,
        workflowExecutionService = workflowExecutionService,
        model = model,
        agentDef = agentDef,
    )

    suspend fun buildApiToolRegistry(
        config: SpolaConfig,
        memoryStore: MemoryStore,
        jobStore: SpolaJobStore,
        kanbanStore: KanbanStore = SqliteKanbanStore(config.database.kanbanDbPath),
        checkpointManager: CheckpointManager,
        model: String = config.provider.defaultModel,
        workflowExecutionService: WorkflowExecutionService? = null,
    ): ToolRegistry = buildToolRegistry(
        config = config,
        includeKanban = true,
        includeScheduler = true,
        checkpointManager = checkpointManager,
        memoryStore = memoryStore,
        schedulerStore = jobStore,
        kanbanStore = kanbanStore,
        workflowExecutionService = workflowExecutionService,
        model = model,
    )

    suspend fun buildMcpToolRegistry(
        config: SpolaConfig,
        memoryStore: MemoryStore,
        schedulerStore: SpolaJobStore?,
        checkpointManager: CheckpointManager,
        coordinator: JvmIndexCoordinator,
        model: String = config.provider.defaultModel,
        workflowExecutionService: WorkflowExecutionService? = null,
    ): ToolRegistry = buildToolRegistry(
        config = config,
        includeKanban = true,
        includeScheduler = schedulerStore != null,
        checkpointManager = checkpointManager,
        memoryStore = memoryStore,
        schedulerStore = schedulerStore,
        coordinator = coordinator,
        workflowExecutionService = workflowExecutionService,
        model = model,
    )

    private fun applyPermissionScoping(registry: ToolRegistry, agentDef: AgentDefinition?) {
        if (agentDef == null) return
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
            ToolPolicy.ALL -> Unit
        }

        val removeNames = mutableListOf<String>()
        for (tool in registry.list()) {
            val name = tool.name.lowercase()
            if (agentDef.filesystemAccess == "none" && (name.contains("file") || name.contains("edit"))) {
                removeNames.add(tool.name)
            } else if (agentDef.filesystemAccess == "read-only" &&
                (name.contains("write_file") || name.contains("edit_file"))
            ) {
                removeNames.add(tool.name)
            }
            if (!agentDef.shellAccess && (name.contains("shell") || name.contains("terminal"))) {
                removeNames.add(tool.name)
            }
            if (!agentDef.networkAccess && (name.contains("web_") || name.contains("http"))) {
                removeNames.add(tool.name)
            }
        }

        for (name in removeNames.distinct()) {
            registry.unregister(name)
        }
    }
}
