package dev.spola.api

import dev.spola.ApiException
import dev.spola.SpolaConfig
import dev.spola.SpolaVersion
import dev.spola.ToolRegistry
import dev.spola.checkpoint.CheckpointManager
import dev.spola.config.SpolaConfigFileStore
import dev.spola.kanban.KanbanStore
import dev.spola.kanban.SqliteKanbanStore
import dev.spola.memory.MemoryStore
import dev.spola.memory.SqliteMemoryStore
import dev.spola.metrics.SpolaMetrics
import dev.spola.scheduler.SpolaJobStore
import dev.spola.scheduler.SqliteSpolaJobStore
import dev.spola.workflow.SqliteWorkflowExecutionStore
import dev.spola.workflow.WorkflowChatService
import dev.spola.workflow.WorkflowExecutionService
import dev.spola.workflow.WorkflowExecutionStore
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.util.UUID

class SpolaApiServer(
    private val config: SpolaConfig = SpolaConfig(),
    private val port: Int = 8082,
    private val host: String = "127.0.0.1",
    private val insecure: Boolean = false,
    private val services: SpolaServices = SpolaServices(config),
) {
    private val trustId: String = UUID.randomUUID().toString()
    private val pairingToken: String = config.pairingToken ?: UUID.randomUUID().toString()

    fun start(wait: Boolean = true) {
        val listenHost = if (insecure && host == "127.0.0.1") "0.0.0.0" else host
        runBlocking { services.start() }

        embeddedServer(CIO, host = listenHost, port = port) {
            spolaApiModule(
                config = config,
                services = services,
                spolaMetrics = services.metrics,
                spolaPort = port,
                spolaPairingToken = pairingToken,
                spolaTrustId = trustId,
                host = listenHost,
                insecure = insecure,
            )
        }.start(wait = wait)
    }
}

fun Application.spolaApiModule(
    config: SpolaConfig = SpolaConfig(),
    agentRunHandler: AgentRunHandler = AgentRunHandler(config),
    jobStore: SpolaJobStore = SqliteSpolaJobStore(config.database.schedulerDbPath),
    memoryStore: MemoryStore = SqliteMemoryStore(config.database.memoryDbPath),
    kanbanStore: KanbanStore = SqliteKanbanStore(config.database.kanbanDbPath),
    workflowExecutionStore: WorkflowExecutionStore = SqliteWorkflowExecutionStore(config.database.workflowsDbPath),
    workflowExecutionService: WorkflowExecutionService = WorkflowExecutionService(
        config = config,
        executionStore = workflowExecutionStore,
        workflowRegistry = WorkflowTemplateRegistry().apply {
            registerBuiltInTemplates()
            registerYamlWorkflows(config)
        },
        chatService = WorkflowChatService(SqliteSessionStore(config.database.sessionsDbPath)),
    ),
    workflowTemplateRegistry: WorkflowTemplateRegistry = WorkflowTemplateRegistry().apply {
        registerBuiltInTemplates()
        registerYamlWorkflows(config)
    },
    toolRegistry: ToolRegistry = runBlocking {
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
    sessionStore: SqliteSessionStore = SqliteSessionStore(config.database.sessionsDbPath),
    host: String = "127.0.0.1",
    insecure: Boolean = false,
    configFileStore: SpolaConfigFileStore = SpolaConfigFileStore(),
    services: SpolaServices? = null,
) {
    val resolvedServices = services
    val effectiveAgentRunHandler = resolvedServices?.agentRunHandler ?: agentRunHandler
    val effectiveJobStore = resolvedServices?.jobStore ?: jobStore
    val effectiveMemoryStore = resolvedServices?.memoryStore ?: memoryStore
    val effectiveKanbanStore = resolvedServices?.kanbanStore ?: kanbanStore
    val effectiveWorkflowExecutionStore = resolvedServices?.workflowExecutionStore ?: workflowExecutionStore
    val effectiveWorkflowExecutionService = resolvedServices?.workflowExecutionService ?: workflowExecutionService
    val effectiveWorkflowTemplateRegistry = resolvedServices?.workflowTemplateRegistry ?: workflowTemplateRegistry
    val effectiveToolRegistry = resolvedServices?.toolRegistry ?: toolRegistry
    val effectiveStreamHandler = resolvedServices?.streamHandler ?: streamHandler
    val effectiveRunState = resolvedServices?.runState ?: runState
    val effectiveCheckpointManager = resolvedServices?.checkpointManager ?: checkpointManager
    val effectiveConfigStore = resolvedServices?.configFileStore ?: configFileStore
    val effectiveSessionStore = resolvedServices?.sessionStore ?: sessionStore

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
        exception<ApiException> { call, cause ->
            call.respond(cause.statusCode, mapOf("error" to (cause.message ?: "request failed")))
        }
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (cause.message ?: "bad request")))
        }
        exception<BadRequestException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (cause.message ?: "bad request")))
        }
        exception<IllegalStateException> { call, cause ->
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (cause.message ?: "internal server error")))
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
                if (requestPath == "/api/health" || requestPath.startsWith("/api/metrics") || requestPath.startsWith("/api/pairing")) return@intercept
                val provided = context.request.headers["Authorization"]
                    ?.takeIf { it.startsWith("Bearer ") }
                    ?.removePrefix("Bearer ")
                    ?.trim()
                ApiAuth.validateApiKeyForHost(config.security.apiKey, provided, host, insecure)
            }

            apiHealthRoutes(version = version)
            apiConfigRoutes(config, effectiveConfigStore)
            apiSessionRoutes(config, effectiveSessionStore, effectiveAgentRunHandler, effectiveStreamHandler)
            apiAgentRoutes(config, effectiveAgentRunHandler, effectiveStreamHandler, effectiveRunState, effectiveToolRegistry)
            apiAgentCrudRoutes(config, providedAgentStore)
            apiMemoryRoutes(config, effectiveMemoryStore)
            apiJobRoutes(config, effectiveJobStore)
            apiKanbanRoutes(config, effectiveKanbanStore)
            apiDeliveryRoutes(config, effectiveToolRegistry)
            apiWorkflowRoutes(config, effectiveWorkflowExecutionService, effectiveWorkflowExecutionStore, effectiveWorkflowTemplateRegistry)
            apiWorkflowSessionRoutes(config, effectiveWorkflowExecutionStore)
            apiCheckpointRoutes(config, effectiveCheckpointManager)
            apiToolRoutes(config, effectiveToolRegistry)
            apiPairingRoutes(spolaPairingToken, spolaPort, spolaTrustId, version)
            apiProviderRoutes(config, effectiveConfigStore)
            apiMetricsRoutes(spolaMetrics)
        }
        apiStaticRoutes()
    }
}
