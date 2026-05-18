package dev.spola.app.app.decompose

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.childContext
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.replaceAll
import com.arkivanov.decompose.value.Value
import dev.spola.app.app.randomUUID
import dev.spola.app.models.PairingInfo
import dev.spola.app.models.TrustState
import dev.spola.app.network.GolemClient
import dev.spola.app.state.AppStateStore
import io.ktor.client.HttpClient
import dev.spola.app.db.OpenClawDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

interface RootComponent {
    val stack: Value<ChildStack<*, Child>>

    sealed class Child {
        class DashboardChild(val component: DashboardComponent) : Child()
        class PairingChild(val component: PairingComponent) : Child()
    }
}

class DefaultRootComponent(
    componentContext: ComponentContext,
    private val db: OpenClawDb? = null,
) : RootComponent, ComponentContext by componentContext {
    private val navigation = StackNavigation<Config>()
    private val stateStore = if (db != null) AppStateStore(db) else null

    /** In-memory trust state for when no database is available (dev mode). */
    private var inMemoryTrust: TrustState? = null

    override val stack: Value<ChildStack<*, RootComponent.Child>> = childStack(
        source = navigation,
        serializer = Config.serializer(),
        initialConfiguration = resolveInitialScreen(),
        handleBackButton = true,
        childFactory = ::child,
    )

    private fun resolveInitialScreen(): Config {
        val stored = stateStore?.loadTrustedHost()
        if (stored?.active == true) return Config.Dashboard
        return Config.Pairing
    }

    private fun child(config: Config, componentContext: ComponentContext): RootComponent.Child = when (config) {
        is Config.Dashboard -> RootComponent.Child.DashboardChild(
            DefaultDashboardComponent(
                componentContext = componentContext,
                stateStore = stateStore,
                inMemoryTrust = inMemoryTrust,
                onOpenPairing = { navigation.replaceAll(Config.Pairing) },
            )
        )

        is Config.Pairing -> RootComponent.Child.PairingChild(
            DefaultPairingComponent(componentContext, stateStore) { trust ->
                inMemoryTrust = trust
                navigation.replaceAll(Config.Dashboard)
            }
        )
    }

    @Serializable
    private sealed class Config {
        @Serializable
        data object Dashboard : Config()

        @Serializable
        data object Pairing : Config()
    }
}

interface PairingComponent {
    val error: StateFlow<String?>
    val isLoading: StateFlow<Boolean>
    fun pair(payload: String)
    fun pairFromUrl(serverUrl: String)
    fun revoke()
}

class DefaultPairingComponent(
    componentContext: ComponentContext,
    private val stateStore: AppStateStore?,
    private val onPairComplete: (TrustState) -> Unit,
) : PairingComponent, ComponentContext by componentContext {
    private val parser = Json { ignoreUnknownKeys = true }
    private val _error = MutableStateFlow<String?>(null)
    override val error: StateFlow<String?> = _error.asStateFlow()
    private val _isLoading = MutableStateFlow(false)
    override val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun pair(payload: String) {
        runCatching { parsePairingPayload(payload) }
            .onSuccess { info ->
                val trust = TrustState(
                    host = info.host,
                    port = info.port,
                    token = info.token,
                    trustId = info.trustId ?: randomUUID(),
                    active = true,
                )
                stateStore?.saveTrustedHost(trust)
                _error.value = null
                onPairComplete(trust)
            }
            .onFailure {
                _error.value = it.message ?: "Invalid configuration payload. Expected JSON with host, port, token."
            }
    }

    override fun pairFromUrl(serverUrl: String) {
        _isLoading.value = true
        _error.value = null
        scope.launch {
            try {
                val httpClient = HttpClient()
                val client = GolemClient(httpClient)
                val info = client.fetchPairingInfo(serverUrl)
                httpClient.close()
                val trust = TrustState(
                    host = info.host,
                    port = info.port,
                    token = info.token,
                    trustId = info.trustId,
                    active = true,
                )
                stateStore?.saveTrustedHost(trust)
                _error.value = null
                _isLoading.value = false
                onPairComplete(trust)
            } catch (e: Exception) {
                _error.value = "Failed to fetch pairing info: ${e.message}"
                _isLoading.value = false
            }
        }
    }

    override fun revoke() {
        stateStore?.revokeTrustedHost()
    }

    private fun parsePairingPayload(rawPayload: String): PairingInfo {
        val payload = rawPayload.trim()
        if (payload.isBlank()) error("Configuration payload is empty")

        val jsonText = extractJsonObject(payload)
        val json = parser.parseToJsonElement(jsonText).jsonObject

        val host = json["host"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: error("Missing field: host")
        val port = json["port"]?.jsonPrimitive?.intOrNull
            ?: error("Missing or invalid field: port")
        val token = json["token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: error("Missing field: token")
        val trustId = json["trustId"]?.jsonPrimitive?.contentOrNull

        return PairingInfo(host, port, token, trustId)
    }

    private fun extractJsonObject(input: String): String {
        val withoutFence = input
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        val payloadRemoved = if (withoutFence.startsWith("Payload:", ignoreCase = true)) {
            withoutFence.substringAfter(":", withoutFence).trim()
        } else {
            withoutFence
        }

        val start = payloadRemoved.indexOf('{')
        val end = payloadRemoved.lastIndexOf('}')
        if (start == -1 || end == -1 || end <= start) {
            error("Could not find a JSON object in the configuration payload")
        }
        return payloadRemoved.substring(start, end + 1)
    }
}

interface DashboardComponent {
    val agentRun: AgentRunComponent
    val toolBrowser: ToolBrowserComponent
    val memorySearch: MemorySearchComponent
    val schedulerList: SchedulerListComponent
    val sessionList: SessionListComponent
    val currentHost: StateFlow<TrustState?>
    val trustedHosts: StateFlow<List<TrustState>>
    val errorMessage: StateFlow<String?>

    fun openPairing()
    fun switchHost(trustId: String)
}

class DefaultDashboardComponent(
    componentContext: ComponentContext,
    private val stateStore: AppStateStore?,
    private val inMemoryTrust: TrustState? = null,
    private val onOpenPairing: () -> Unit,
) : DashboardComponent, ComponentContext by componentContext {
    private var client: GolemClient? = null
    private val _currentHost = MutableStateFlow<TrustState?>(null)
    override val currentHost: StateFlow<TrustState?> = _currentHost.asStateFlow()
    private val _trustedHosts = MutableStateFlow<List<TrustState>>(emptyList())
    override val trustedHosts: StateFlow<List<TrustState>> = _trustedHosts.asStateFlow()
    private val _errorMessage = MutableStateFlow<String?>(null)
    override val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    override val agentRun = DefaultAgentRunComponent(
        componentContext = childContext("agent-run"),
        clientProvider = { client },
        reportFailure = ::reportFailure,
    )
    override val toolBrowser = DefaultToolBrowserComponent(
        componentContext = childContext("tool-browser"),
        clientProvider = { client },
        reportFailure = ::reportFailure,
    )
    override val memorySearch = DefaultMemorySearchComponent(
        componentContext = childContext("memory-search"),
        clientProvider = { client },
        reportFailure = ::reportFailure,
    )
    override val schedulerList = DefaultSchedulerListComponent(
        componentContext = childContext("scheduler-list"),
        clientProvider = { client },
        reportFailure = ::reportFailure,
    )
    override val sessionList = DefaultSessionListComponent(
        componentContext = childContext("session-list"),
        clientProvider = { client },
        stateStore = stateStore,
        reportFailure = ::reportFailure,
    )

    init {
        loadHosts()
        // Re-fetch sessions/models now that client is initialized
        sessionList.refresh()
    }

    override fun openPairing() = onOpenPairing()

    private fun loadHosts() {
        val hosts = stateStore?.loadTrustedHosts().orEmpty()
        val active = stateStore?.loadTrustedHost()?.takeIf { it.active }
        _trustedHosts.value = hosts
        // Use in-memory trust if no DB, otherwise prefer DB
        val resolved = active ?: inMemoryTrust
        _currentHost.value = resolved
        resetClient(resolved)
        if (resolved == null) {
            onOpenPairing()
        }
    }

    override fun switchHost(trustId: String) {
        val selected = _trustedHosts.value.firstOrNull { it.trustId == trustId } ?: return
        stateStore?.saveTrustedHost(selected.copy(active = true))
        loadHosts()
    }

    private fun resetClient(trust: TrustState?) {
        client?.close()
        client = trust?.let { GolemClient(HttpClient(), "http://${it.host}:${it.port}/", it.token) }
    }

    private fun reportFailure(error: Throwable, status: String?) {
        _errorMessage.value = status ?: error.message
    }
}
