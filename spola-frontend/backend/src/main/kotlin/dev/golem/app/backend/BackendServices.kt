package dev.spola.app.backend

import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import dev.spola.app.db.OpenClawDb
import dev.spola.app.backend.manager.CommandManager
import dev.spola.app.backend.manager.FlowManager
import dev.spola.app.backend.repo.*
import dev.spola.app.backend.network.*
import dev.spola.app.models.KanbanCard
import dev.spola.app.models.WorkflowDefinition
import dev.spola.app.state.AppStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

import io.ktor.client.plugins.*
import java.util.concurrent.CopyOnWriteArrayList

class BackendServices(
    val db: OpenClawDb,
    val ollamaClient: HttpClient,
    val stateStore: AppStateStore = AppStateStore(db),
    val auditRepository: AuditRepository = SqlAuditRepository(db),
    val sessionRepository: SessionRepository = SqlSessionRepository(db, stateStore),
    val messageRepository: MessageRepository = SqlMessageRepository(db),
    val fileRepository: FileRepository = SqlFileRepository(db),
    val flowManager: FlowManager = FlowManager(),
    val commandManager: CommandManager = CommandManager(auditRepository, flowManager),
    val restGatewayClient: OpenClawRestGatewayClient = OpenClawRestGatewayClient(
        client = HttpClient(ollamaClient.engine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 600000 // 10 minutes
                connectTimeoutMillis = 20000  // 20 seconds
                socketTimeoutMillis = 600000  // 10 minutes
            }
        },
        baseUrl = "http://127.0.0.1:${OpenClawConfig.getGatewayPort()}",
        token = OpenClawConfig.getGatewayToken()
    ),
    val modelCatalogService: ModelCatalogService = DefaultModelCatalogService(
        ollamaModelSource = KtorOllamaModelSource(ollamaClient),
        restGatewayClient = restGatewayClient,
    ),
    val chatProviders: Map<String, ChatProvider> = listOf(
        OllamaChatProvider(ollamaClient),
        OpenClawGatewayChatProvider(restGatewayClient)
    ).associateBy { it.id },
    val chatRoutingService: ChatRoutingService = ChatRoutingService(db, stateStore, chatProviders, messageRepository, fileRepository),
    val speechService: SpeechService = SpeechService(
        stt = OllamaWhisperSTT(ollamaClient),
        tts = SystemTTS()
    ),
    val backendMetaService: BackendMetaService = BackendMetaService(),
    val kanbanCards: CopyOnWriteArrayList<KanbanCard> = CopyOnWriteArrayList(),
    val workflowDefinitions: CopyOnWriteArrayList<WorkflowDefinition> = CopyOnWriteArrayList(
        listOf(
            WorkflowDefinition(
                id = "session-handoff",
                name = "Session Handoff",
                description = "Carry active context into follow-up tasks between sessions.",
                enabled = true,
            ),
            WorkflowDefinition(
                id = "artifact-review",
                name = "Artifact Review",
                description = "Queue a lightweight review pass after generated files change.",
                enabled = false,
            ),
            WorkflowDefinition(
                id = "nightly-memory-sync",
                name = "Nightly Memory Sync",
                description = "Refresh memory summaries from recent project activity.",
                enabled = true,
            ),
        ),
    )
) {
    init {
        // Initialization logic here
    }
}
