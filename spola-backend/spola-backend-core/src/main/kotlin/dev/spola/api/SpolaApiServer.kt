package dev.spola.api

import dev.spola.SpolaConfig
import dev.spola.SpolaVersion
import dev.spola.ToolRegistry
import dev.spola.api.AgentRunHandler
import dev.spola.api.AgentRunState
import dev.spola.api.ApiAuth
import dev.spola.api.apiAgentRoutes
import dev.spola.api.apiAgentCrudRoutes
import dev.spola.api.apiCheckpointRoutes
import dev.spola.api.apiConfigRoutes
import dev.spola.api.apiDeliveryRoutes
import dev.spola.api.apiHealthRoutes
import dev.spola.api.apiJobRoutes
import dev.spola.api.apiKanbanRoutes
import dev.spola.api.apiMemoryRoutes
import dev.spola.api.apiMetricsRoutes
import dev.spola.api.apiPairingRoutes
import dev.spola.api.apiProviderRoutes
import dev.spola.api.apiSessionRoutes
import dev.spola.api.apiStaticRoutes
import dev.spola.api.apiToolRoutes
import dev.spola.api.apiWorkflowRoutes
import dev.spola.api.apiWorkflowSessionRoutes
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
import dev.spola.workflow.SqliteWorkflowExecutionStore
import dev.spola.workflow.AsyncWorkflowDispatcher
import dev.spola.workflow.WorkflowChatService
import dev.spola.workflow.WorkflowExecutionService
import dev.spola.workflow.WorkflowExecutionStore
import dev.spola.workflow.WorkflowKanbanService
import dev.spola.workflow.WorkflowTemplateRegistry
import dev.spola.workflow.registerBuiltInTemplates
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import kotlinx.serialization.json.Json
import java.util.UUID

class SpolaApiServer(
    private val config: SpolaConfig = SpolaConfig(),
    private val port: Int = 8082,
    private val host: String = "127.0.0.1",
    private val insecure: Boolean = false,
    private val runState: AgentRunState = AgentRunState(),
    private val agentRunHandler: AgentRunHandler = AgentRunHandler(config, runState = runState),
    private val jobStoreFactory: (String) -> SpolaJobStore = ::SqliteSpolaJobStore,
    private val memoryStoreFactory: (String) -> MemoryStore = ::SqliteMemoryStore,
    private val checkpointStoreFactory: (String) -> CheckpointStore = ::CheckpointStore,
    private val spolaMetrics: SpolaMetrics? = null,
    private val configFileStore: SpolaConfigFileStore = SpolaConfigFileStore(),
) {
    private val jobStore = jobStoreFactory(config.schedulerDbPath)
    private val memoryStore = memoryStoreFactory(config.memoryDbPath)
    private val checkpointStore = checkpointStoreFactory(config.checkpointDbPath)
    private val checkpointManager = CheckpointManager(checkpointStore)
    private val sessionStore = SqliteSessionStore(config.sessionsDbPath)
    private val workflowChatService = WorkflowChatService(sessionStore)
    private val workflowExecutionStore: WorkflowExecutionStore = SqliteWorkflowExecutionStore(config.workflowDbPath)
    private val workflowTemplateRegistry = WorkflowTemplateRegistry().apply {
        registerBuiltInTemplates()
        registerYamlWorkflows(config)
    }
    private val workflowExecutionService = WorkflowExecutionService(
        config = config,
        executionStore = workflowExecutionStore,
        workflowRegistry = workflowTemplateRegistry,
        chatService = workflowChatService,
    )
    private val workflowKanbanService = WorkflowKanbanService(
        executionService = workflowExecutionService,
        cooldownSeconds = config.kanbanWorkflowCooldownSeconds,
    )
    private val kanbanStore: KanbanStore = SqliteKanbanStore(
        dbPath = config.kanbanDbPath,
        onStatusChanged = workflowKanbanService::onTaskStatusChanged,
    )
    private val workflowDispatcher: AsyncWorkflowDispatcher? = if (config.workflowDispatcherConfig.enabled) {
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
    private val toolRegistry = kotlinx.coroutines.runBlocking {
        dev.spola.factory.ToolRegistryFactory.buildApiToolRegistry(
            config = config,
            memoryStore = memoryStore,
            jobStore = jobStore,
            kanbanStore = kanbanStore,
            checkpointManager = checkpointManager,
            workflowExecutionService = workflowExecutionService,
        )
    }
    private val streamHandler = StreamHandler(agentRunHandler)
    private val trustId: String = UUID.randomUUID().toString()
    private val pairingToken: String = config.pairingToken ?: UUID.randomUUID().toString()

    fun start(wait: Boolean = true) {
        val listenHost = if (insecure && host == "127.0.0.1") "0.0.0.0" else host

        // Start workflow dispatcher (non-blocking — runs in its own coroutine scope)
        kotlinx.coroutines.runBlocking {
            workflowDispatcher?.start()
        }

        embeddedServer(CIO, host = listenHost, port = port) {
            spolaApiModule(
                config = config,
                agentRunHandler = agentRunHandler,
                jobStore = jobStore,
                memoryStore = memoryStore,
                toolRegistry = toolRegistry,
                streamHandler = streamHandler,
                runState = runState,
                spolaMetrics = spolaMetrics,
                spolaPort = port,
                spolaPairingToken = pairingToken,
                spolaTrustId = trustId,
                sessionStore = sessionStore,
                host = host,
                insecure = insecure,
                configFileStore = configFileStore,
                kanbanStore = kanbanStore,
                workflowExecutionService = workflowExecutionService,
                workflowExecutionStore = workflowExecutionStore,
                workflowTemplateRegistry = workflowTemplateRegistry,
            )
        }.start(wait = wait)
    }
}

fun Application.spolaApiModule(
    config: SpolaConfig = SpolaConfig(),
    agentRunHandler: AgentRunHandler,
    jobStore: SpolaJobStore,
    memoryStore: MemoryStore = SqliteMemoryStore(config.memoryDbPath),
    kanbanStore: KanbanStore = SqliteKanbanStore(config.kanbanDbPath),
    workflowExecutionStore: WorkflowExecutionStore = SqliteWorkflowExecutionStore(config.workflowDbPath),
    workflowExecutionService: WorkflowExecutionService = WorkflowExecutionService(
        config = config,
        executionStore = workflowExecutionStore,
        workflowRegistry = WorkflowTemplateRegistry().apply {
            registerBuiltInTemplates()
            registerYamlWorkflows(config)
        },
        chatService = WorkflowChatService(SqliteSessionStore(config.sessionsDbPath)),
    ),
    workflowTemplateRegistry: WorkflowTemplateRegistry = WorkflowTemplateRegistry().apply {
        registerBuiltInTemplates()
        registerYamlWorkflows(config)
    },
    toolRegistry: ToolRegistry = kotlinx.coroutines.runBlocking {
        dev.spola.factory.ToolRegistryFactory.buildApiToolRegistry(
            config = config,
            memoryStore = memoryStore,
            jobStore = jobStore,
            kanbanStore = kanbanStore,
            checkpointManager = CheckpointManager.fromConfig(config),
        )
    },
    streamHandler: StreamHandler = StreamHandler(agentRunHandler),
    runState: AgentRunState = AgentRunState(),
    checkpointManager: CheckpointManager = CheckpointManager.fromConfig(config),
    version: String = SpolaVersion.VERSION,
    spolaMetrics: SpolaMetrics? = null,
    spolaPort: Int = 8082,
    spolaPairingToken: String = "",
    spolaTrustId: String = "",
    providedAgentStore: dev.spola.agent.AgentStore? = null,
    sessionStore: SqliteSessionStore = SqliteSessionStore(config.sessionsDbPath),
    host: String = "127.0.0.1",
    insecure: Boolean = false,
    configFileStore: SpolaConfigFileStore = SpolaConfigFileStore(),
) {
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        })
    }
    install(SSE)
    install(StatusPages) {
        exception<MissingApiKeyException> { call, cause ->
            call.respondAuthFailure(cause)
        }
        exception<InvalidApiKeyException> { call, cause ->
            call.respondAuthFailure(cause)
        }
        exception<IllegalArgumentException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to (cause.message ?: "bad request")),
            )
        }
        exception<BadRequestException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to (cause.message ?: "bad request")),
            )
        }
        exception<IllegalStateException> { call, cause ->
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to (cause.message ?: "internal server error")),
            )
        }
        exception<Exception> { call, cause ->
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf(
                    "error" to (cause.message ?: "unexpected error"),
                    "type" to (cause::class.simpleName ?: "Unknown"),
                ),
            )
        }
    }

    routing {
        route("/api") {
            intercept(ApplicationCallPipeline.Plugins) {
                val requestPath = context.request.path()
                if (requestPath == "/api/health" || requestPath.startsWith("/api/metrics")) return@intercept
                val provided = context.request.headers["Authorization"]
                    ?.takeIf { it.startsWith("Bearer ") }
                    ?.removePrefix("Bearer ")
                    ?.trim()
                ApiAuth.validateApiKeyForHost(config.apiKey, provided, host, insecure)
            }

            apiHealthRoutes(version = version)
            apiConfigRoutes(config, configFileStore)
            apiSessionRoutes(config, sessionStore, agentRunHandler, streamHandler)
            apiAgentRoutes(config, agentRunHandler, streamHandler, runState, toolRegistry)
            apiAgentCrudRoutes(config, providedAgentStore)
            apiMemoryRoutes(config, memoryStore)
            apiJobRoutes(config, jobStore)
            apiKanbanRoutes(config, kanbanStore)
            apiDeliveryRoutes(config, toolRegistry)
            apiWorkflowRoutes(config, workflowExecutionService, workflowExecutionStore, workflowTemplateRegistry)
            apiWorkflowSessionRoutes(config, workflowExecutionStore)
            apiCheckpointRoutes(config, checkpointManager)
            apiToolRoutes(config, toolRegistry)
            apiPairingRoutes(spolaPairingToken, spolaPort, spolaTrustId, version)
            apiProviderRoutes(config, configFileStore)
            apiMetricsRoutes(spolaMetrics)
        }
        apiStaticRoutes()
    }
}
