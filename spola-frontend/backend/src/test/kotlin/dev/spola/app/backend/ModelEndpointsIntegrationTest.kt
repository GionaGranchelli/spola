package dev.spola.app.backend

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import dev.spola.app.backend.network.OpenAiModel
import dev.spola.app.backend.network.SpolaRestGatewayClient
import dev.spola.app.db.SpolaDb
import dev.spola.app.models.ModelInfo
import dev.spola.app.models.SpolaOptions
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
                    spolaModels = listOf(
                        OpenAiModel(id = "spola/main", ownedBy = "spola"),
                        OpenAiModel(id = "spola/reviewer", ownedBy = "spola"),
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
        assertTrue(models.any { it.id == "spola/main" && it.provider == "spola" })
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
                    spolaError = RuntimeException("Daemon down"),
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
    fun spolaOptionsEndpointUsesDaemonModels() = testApplication {
        val db = createInMemoryDb()
        trust(db)
        application {
            val services = BackendServices(
                db = db,
                ollamaClient = ollamaClient,
                modelCatalogService = createCatalogService(
                    ollamaModels = emptyList(),
                    spolaModels = listOf(
                        OpenAiModel(id = "spola/main", ownedBy = "spola"),
                        OpenAiModel(id = "spola/reviewer", ownedBy = "spola"),
                    ),
                ),
                backendMetaService = BackendMetaService(version = "test", buildTime = "test"),
            )
            module(db = db, servicesOverride = services)
        }

        val response = client.get("/spola/options") {
            header(HttpHeaders.Authorization, authHeader)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val options = json.decodeFromString<SpolaOptions>(response.bodyAsText())
        assertEquals(2, options.agents.size)
        assertTrue(options.agents.any { it.id == "main" })
        assertTrue(options.agents.any { it.id == "reviewer" })
    }

    @Test
    fun spolaOptionsEndpointReturns200WhenCatalogServiceThrowsUnexpectedly() = testApplication {
        val db = createInMemoryDb()
        trust(db)
        application {
            val services = BackendServices(
                db = db,
                ollamaClient = ollamaClient,
                modelCatalogService = object : ModelCatalogService {
                    override suspend fun listModels(): CatalogResponse<List<ModelInfo>> = CatalogResponse(emptyList())

                    override suspend fun getSpolaOptions(): CatalogResponse<SpolaOptions> {
                        error("unexpected options crash")
                    }
                },
                backendMetaService = BackendMetaService(version = "test", buildTime = "test"),
            )
            module(db = db, servicesOverride = services)
        }

        val response = client.get("/spola/options") {
            header(HttpHeaders.Authorization, authHeader)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val options = json.decodeFromString<SpolaOptions>(response.bodyAsText())
        assertTrue(options.agents.isEmpty())
        assertTrue(options.models.isEmpty())
    }

    private fun createCatalogService(
        ollamaModels: List<ModelInfo> = emptyList(),
        ollamaError: Throwable? = null,
        spolaModels: List<OpenAiModel> = emptyList(),
        spolaError: Throwable? = null,
    ): ModelCatalogService {
        val ollamaSource = OllamaModelSource {
            if (ollamaError != null) throw ollamaError
            ollamaModels
        }
        val restGatewayClient = object : SpolaRestGatewayClient(
            io.ktor.client.HttpClient(), "http://localhost:18789", "test-token"
        ) {
            override suspend fun getModels(): List<OpenAiModel> {
                if (spolaError != null) throw spolaError
                return spolaModels
            }
        }
        return DefaultModelCatalogService(ollamaSource, restGatewayClient)
    }

    private fun createInMemoryDb(): SpolaDb {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        SpolaDb.Schema.create(driver)
        return SpolaDb(driver)
    }

    private fun trust(db: SpolaDb) {
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
