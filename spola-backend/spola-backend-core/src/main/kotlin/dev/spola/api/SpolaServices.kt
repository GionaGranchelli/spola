package dev.spola.api

import dev.spola.SpolaConfig
import dev.spola.ToolRegistry
import dev.spola.checkpoint.CheckpointManager
import dev.spola.checkpoint.CheckpointStore
import dev.spola.config.SpolaConfigFileStore
import dev.spola.kanban.KanbanStore
import dev.spola.kanban.SqliteKanbanStore
import dev.spola.memory.MemoryStore
import dev.spola.memory.SqliteMemoryStore
import dev.spola.metrics.SpolaMetrics
import dev.spola.scheduler.SpolaJobStore
import dev.spola.scheduler.SqliteSpolaJobStore
import dev.spola.workflow.AsyncWorkflowDispatcher
import dev.spola.workflow.SqliteWorkflowExecutionStore
import dev.spola.workflow.WorkflowChatService
import dev.spola.workflow.WorkflowExecutionService
import dev.spola.workflow.WorkflowExecutionStore
import dev.spola.workflow.WorkflowKanbanService
import dev.spola.workflow.WorkflowTemplateRegistry
import dev.spola.workflow.registerBuiltInTemplates
import kotlinx.coroutines.runBlocking

class SpolaServices(
    val config: SpolaConfig,
    val runState: AgentRunState = AgentRunState(),
    val agentRunHandler: AgentRunHandler = AgentRunHandler(config, runState = runState),
    val configFileStore: SpolaConfigFileStore = SpolaConfigFileStore(),
    val metrics: SpolaMetrics? = null,
) {
    val jobStore: SpolaJobStore = SqliteSpolaJobStore(config.database.schedulerDbPath)
    val memoryStore: MemoryStore = SqliteMemoryStore(config.database.memoryDbPath)
    val checkpointStore: CheckpointStore = CheckpointStore(config.database.checkpointDbPath)
    val checkpointManager: CheckpointManager = CheckpointManager(checkpointStore)
    val sessionStore: SqliteSessionStore = SqliteSessionStore(config.database.sessionsDbPath)
    val workflowChatService: WorkflowChatService = WorkflowChatService(sessionStore)
    val workflowExecutionStore: WorkflowExecutionStore = SqliteWorkflowExecutionStore(config.database.workflowsDbPath)
    val workflowTemplateRegistry: WorkflowTemplateRegistry = WorkflowTemplateRegistry().apply {
        registerBuiltInTemplates()
        registerYamlWorkflows(config)
    }
    val workflowExecutionService: WorkflowExecutionService = WorkflowExecutionService(
        config = config,
        executionStore = workflowExecutionStore,
        workflowRegistry = workflowTemplateRegistry,
        chatService = workflowChatService,
    )
    val workflowKanbanService: WorkflowKanbanService = WorkflowKanbanService(
        executionService = workflowExecutionService,
        cooldownSeconds = config.kanbanWorkflowCooldownSeconds,
    )
    val kanbanStore: KanbanStore = SqliteKanbanStore(
        dbPath = config.database.kanbanDbPath,
        onStatusChanged = workflowKanbanService::onTaskStatusChanged,
    )
    val workflowDispatcher: AsyncWorkflowDispatcher? = if (config.workflowDispatcherConfig.enabled) {
        AsyncWorkflowDispatcher(
            executionStore = workflowExecutionStore,
            executionService = workflowExecutionService,
            pollIntervalMs = config.workflowDispatcherConfig.pollIntervalMs,
            batchSize = config.workflowDispatcherConfig.batchSize,
            globalMaxConcurrent = config.workflowDispatcherConfig.globalMaxConcurrent,
            perUserMaxConcurrent = config.workflowDispatcherConfig.perUserMaxConcurrent,
        )
    } else {
        null
    }
    val toolRegistry: ToolRegistry by lazy {
        runBlocking {
            dev.spola.factory.ToolRegistryFactory.buildApiToolRegistry(
                config = config,
                memoryStore = memoryStore,
                jobStore = jobStore,
                kanbanStore = kanbanStore,
                checkpointManager = checkpointManager,
                workflowExecutionService = workflowExecutionService,
            )
        }
    }
    val streamHandler: StreamHandler = StreamHandler(agentRunHandler)

    suspend fun start(): SpolaServices {
        workflowDispatcher?.start()
        return this
    }

    fun shutdown() {
        runBlocking {
            workflowDispatcher?.stop()
        }
        workflowExecutionStore.close()
        sessionStore.close()
        checkpointStore.close()
        memoryStore.close()
        jobStore.close()
    }
}
