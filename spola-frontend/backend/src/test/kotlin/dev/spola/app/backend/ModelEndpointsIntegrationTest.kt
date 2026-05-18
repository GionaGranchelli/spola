package dev.spola.app.backend

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import dev.spola.app.backend.network.OpenAiModel
import dev.spola.app.backend.network.OpenClawRestGatewayClient
import dev.spola.app.db.OpenClawDb
import dev.spola.app.models.ModelInfo
import dev.spola.app.models.OpenClawOptions
import dev.spola.app.models.TrustState
import dev.spola.app.state.AppStateStore
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelEndpointsIntegrationTest {
    private val authHeader = "Bearer test-token"
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun modelsEndpointMergesAndDedupesAcrossSources() = testApplication {
        val db = createInMemoryDb()
        trust(db)
        application {
            val services = BackendServices(
                db = db,
                ollamaClient = ollamaClient,
                modelCatalogService = createCatalogService(
                    ollamaModels = listOf(
                        ModelInfo(id = "gemma4:e2b", name = "gemma4:e2b", provider = "ollama"),
                        ModelInfo(id = "qwen3:8b", name = "qwen3:8b", provider = "ollama"),
                    ),
                    openClawModels = listOf(
                        OpenAiModel(id = "openclaw/main", ownedBy = "openclaw"),
                        OpenAiModel(id = "openclaw/reviewer", ownedBy = "openclaw"),
                    )
                ),
                backendMetaService = BackendMetaService(version = "test", buildTime = "test"),
            )
            module(db = db, servicesOverride = services)
        }

        val response = client.get("/models") {
            header(HttpHeaders.Authorization, authHeader)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val models = json.decodeFromString<List<ModelInfo>>(response.bodyAsText())

        assertTrue(models.any { it.id == "gemma4:e2b" && it.provider == "ollama" })
        assertTrue(models.any { it.id == "qwen3:8b" && it.provider == "ollama" })
        assertTrue(models.any { it.id == "openclaw/main" && it.provider == "openclaw" })
    }

    @Test
    fun modelsEndpointReturns200WhenOllamaUnavailableAndDaemonFails() = testApplication {
        val db = createInMemoryDb()
        trust(db)
        application {
            val services = BackendServices(
                db = db,
                ollamaClient = ollamaClient,
                modelCatalogService = createCatalogService(
                    ollamaError = IllegalStateException("Ollama down"),
                    openClawError = RuntimeException("Daemon down"),
                ),
                backendMetaService = BackendMetaService(version = "test", buildTime = "test"),
            )
            module(db = db, servicesOverride = services)
        }

        val response = client.get("/models") {
            header(HttpHeaders.Authorization, authHeader)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val models = json.decodeFromString<List<ModelInfo>>(response.bodyAsText())
        assertTrue(models.isEmpty())
    }

    @Test
    fun openClawOptionsEndpointUsesDaemonModels() = testApplication {
        val db = createInMemoryDb()
        trust(db)
        application {
            val services = BackendServices(
                db = db,
                ollamaClient = ollamaClient,
                modelCatalogService = createCatalogService(
                    ollamaModels = emptyList(),
                    openClawModels = listOf(
                        OpenAiModel(id = "openclaw/main", ownedBy = "openclaw"),
                        OpenAiModel(id = "openclaw/reviewer", ownedBy = "openclaw"),
                    ),
                ),
                backendMetaService = BackendMetaService(version = "test", buildTime = "test"),
            )
            module(db = db, servicesOverride = services)
        }

        val response = client.get("/openclaw/options") {
            header(HttpHeaders.Authorization, authHeader)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val options = json.decodeFromString<OpenClawOptions>(response.bodyAsText())
        assertEquals(2, options.agents.size)
        assertTrue(options.agents.any { it.id == "main" })
        assertTrue(options.agents.any { it.id == "reviewer" })
    }

    @Test
    fun openClawOptionsEndpointReturns200WhenCatalogServiceThrowsUnexpectedly() = testApplication {
        val db = createInMemoryDb()
        trust(db)
        application {
            val services = BackendServices(
                db = db,
                ollamaClient = ollamaClient,
                modelCatalogService = object : ModelCatalogService {
                    override suspend fun listModels(): CatalogResponse<List<ModelInfo>> = CatalogResponse(emptyList())

                    override suspend fun getOpenClawOptions(): CatalogResponse<OpenClawOptions> {
                        error("unexpected options crash")
                    }
                },
                backendMetaService = BackendMetaService(version = "test", buildTime = "test"),
            )
            module(db = db, servicesOverride = services)
        }

        val response = client.get("/openclaw/options") {
            header(HttpHeaders.Authorization, authHeader)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val options = json.decodeFromString<OpenClawOptions>(response.bodyAsText())
        assertTrue(options.agents.isEmpty())
        assertTrue(options.models.isEmpty())
    }

    private fun createCatalogService(
        ollamaModels: List<ModelInfo> = emptyList(),
        ollamaError: Throwable? = null,
        openClawModels: List<OpenAiModel> = emptyList(),
        openClawError: Throwable? = null,
    ): ModelCatalogService {
        val ollamaSource = OllamaModelSource {
            if (ollamaError != null) throw ollamaError
            ollamaModels
        }
        val restGatewayClient = object : OpenClawRestGatewayClient(
            io.ktor.client.HttpClient(), "http://localhost:18789", "test-token"
        ) {
            override suspend fun getModels(): List<OpenAiModel> {
                if (openClawError != null) throw openClawError
                return openClawModels
            }
        }
        return DefaultModelCatalogService(ollamaSource, restGatewayClient)
    }

    private fun createInMemoryDb(): OpenClawDb {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OpenClawDb.Schema.create(driver)
        return OpenClawDb(driver)
    }

    private fun trust(db: OpenClawDb) {
        AppStateStore(db).saveTrustedHost(
            TrustState(
                host = "127.0.0.1",
                port = 9090,
                token = "test-token",
                trustId = "trust-test",
                active = true,
            )
        )
    }

    private fun fixturePath(name: String): String {
        return checkNotNull(javaClass.getResource("/fixtures/$name")) { "Missing fixture: $name" }
            .toURI()
            .path
    }
}
