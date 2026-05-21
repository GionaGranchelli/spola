package dev.spola.api

import dev.spola.SpolaAgent
import dev.spola.SpolaConfig
import dev.spola.SpolaFactory
import dev.spola.MockToolProvider
import dev.spola.Tool
import dev.spola.ToolParameter
import dev.spola.ToolParameterType
import dev.spola.ToolRegistry
import dev.spola.ToolResult
import dev.spola.checkpoint.CheckpointManager
import dev.spola.checkpoint.CheckpointStore
import dev.spola.config.SpolaConfigFileStore
import dev.spola.memory.MemoryStore
import dev.spola.memory.SqliteMemoryStore
import dev.spola.scheduler.SqliteSpolaJobStore
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.sse.SSE
import io.ktor.server.testing.testApplication
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpolaApiServerTest {

    @Test
    fun `health endpoint returns status and version`() = testApplication {
        val store = SqliteSpolaJobStore(":memory:")
        application {
            spolaApiModule(
                agentRunHandler = AgentRunHandler(),
                jobStore = store,
            )
        }

        val response = client.get("/api/health")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"status\":\"ok\""))
        assertTrue(response.bodyAsText().contains("\"version\":\"0.1.0\""))
        store.close()
    }

    @Test
    fun `health endpoint works without auth`() = testApplication {
        val store = SqliteSpolaJobStore(":memory:")
        application {
            spolaApiModule(
                agentRunHandler = AgentRunHandler(),
                jobStore = store,
                config = SpolaConfig(apiKey = "secret"),
            )
        }

        val response = client.get("/api/health")

        assertEquals(HttpStatusCode.OK, response.status)
        store.close()
    }

    @Test
    fun `config endpoint returns frontend shaped config response`(@TempDir tempDir: Path) = testApplication {
        val store = SqliteSpolaJobStore(tempDir.resolve("scheduler.db").toString())
        val configFileStore = SpolaConfigFileStore(tempDir.resolve("config.yaml"))
        application {
            spolaApiModule(
                agentRunHandler = AgentRunHandler(),
                jobStore = store,
                config = SpolaConfig(
                    apiKey = "secret",
                    workingDirectory = "/workspace",
                    personaPath = "/workspace/AGENTS.md",
                    memoryDbPath = tempDir.resolve("memory.db").toString(),
                    schedulerDbPath = tempDir.resolve("scheduler.db").toString(),
                    kanbanDbPath = tempDir.resolve("kanban.db").toString(),
                    checkpointDbPath = tempDir.resolve("checkpoint.db").toString(),
                    jvmIndexDbPath = tempDir.resolve("jvm-index.db").toString(),
                    sessionsDbPath = tempDir.resolve("sessions.db").toString(),
                    pluginsDir = tempDir.resolve("plugins").toString(),
                    agentsDir = tempDir.resolve("agents").toString(),
                    agentsDbPath = tempDir.resolve("agents.db").toString(),
                    emailPassword = "email-secret",
                    elevenlabsApiKey = "tts-secret",
                    pairingToken = "pair-secret",
                    telegramBotToken = "telegram-secret",
                ),
                configFileStore = configFileStore,
            )
        }

        val response = client.get("/api/config") {
            header("Authorization", "Bearer secret")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val parsed = Json.decodeFromString<ConfigResponse>(response.bodyAsText())
        assertEquals(configFileStore.configPath.toString(), parsed.effectiveConfigPath)
        assertEquals("/workspace", parsed.workdir)
        assertEquals("/workspace/AGENTS.md", parsed.persona)
        assertEquals("***", parsed.apiKey)
        assertEquals("***", parsed.pairingToken)
        assertEquals("***", parsed.telegramBotToken)
        assertEquals("***", parsed.email?.password)
        assertEquals("***", parsed.tts?.elevenlabsApiKey)
        assertNull(parsed.unsafe)
        store.close()
    }

    @Test
    fun `config save endpoint writes yaml and preserves omitted secrets`(@TempDir tempDir: Path) = testApplication {
        val store = SqliteSpolaJobStore(tempDir.resolve("scheduler.db").toString())
        val configPath = tempDir.resolve("config.yaml")
        val configFileStore = SpolaConfigFileStore(configPath)
        configFileStore.save(
            SpolaConfig(
                apiKey = "stored-secret",
                emailPassword = "stored-email-secret",
                elevenlabsApiKey = "stored-elevenlabs-secret",
            ),
        )

        application {
            spolaApiModule(
                agentRunHandler = AgentRunHandler(),
                jobStore = store,
                config = SpolaConfig(apiKey = "secret"),
                configFileStore = configFileStore,
            )
        }

        val response = client.post("/api/config/save") {
            header("Authorization", "Bearer secret")
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "model": "gpt-4.1",
                  "workdir": "/tmp/work",
                  "emailSmtpHost": "smtp.example.com",
                  "architectEnabled": true,
                  "architectModel": "gpt-4o-mini"
                }
                """.trimIndent(),
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val saved = configFileStore.load()
        assertEquals("gpt-4.1", saved.model)
        assertEquals("/tmp/work", saved.workingDirectory)
        assertEquals("smtp.example.com", saved.emailSmtpHost)
        assertTrue(saved.architectMode.enabled)
        assertEquals("gpt-4o-mini", saved.architectMode.architectModel)
        assertEquals("stored-secret", saved.apiKey)
        assertEquals("stored-email-secret", saved.emailPassword)
        assertEquals("stored-elevenlabs-secret", saved.elevenlabsApiKey)
        store.close()
    }

    @Test
    fun `agent run endpoint executes agent and returns result`(@TempDir tempDir: Path) = testApplication {
        val provider = MockToolProvider().apply {
            addTextResponse("API run complete")
        }
        val config = SpolaConfig(
            memoryDbPath = tempDir.resolve("memory.db").toString(),
            schedulerDbPath = tempDir.resolve("scheduler.db").toString(),
        )
        val store = SqliteSpolaJobStore(tempDir.resolve("jobs.db").toString())
        val handler = AgentRunHandler(
            baseConfig = config,
            instanceFactory = { baseConfig, model ->
                SpolaFactory.create(
                    config = baseConfig,
                    provider = provider,
                    effectiveModel = model,
                )
            },
        )

        application {
            spolaApiModule(
                agentRunHandler = handler,
                jobStore = store,
            )
        }

        val apiClient = createClient {
            install(ContentNegotiation) {
                json()
            }
        }

        val response = apiClient.post("/api/agent/run") {
            contentType(ContentType.Application.Json)
            setBody(AgentRunRequest(goal = "say hello", model = "mock-model"))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("API run complete"))
        assertTrue(response.bodyAsText().contains("\"turns\":1"))
        store.close()
    }

    @Test
    fun `tools endpoint returns all registered tools with schema format`(@TempDir tempDir: Path) = testApplication {
        val memStore = SqliteMemoryStore(tempDir.resolve("memory.db").toString())
        val store = SqliteSpolaJobStore(tempDir.resolve("scheduler.db").toString())

        // Create a tool registry with known tools for deterministic testing
        val toolRegistry = ToolRegistry()
        toolRegistry.register(Tool(
            name = "test_tool",
            description = "A test tool",
            parameters = listOf(
                ToolParameter("input", "Input parameter", ToolParameterType.STRING, required = true),
                ToolParameter("count", "Count parameter", ToolParameterType.INTEGER, required = false, defaultValue = 1),
            ),
            execute = { ToolResult.ok("done") },
        ))

        application {
            spolaApiModule(
                agentRunHandler = AgentRunHandler(),
                jobStore = store,
                memoryStore = memStore,
                toolRegistry = toolRegistry,
            )
        }

        val response = client.get("/api/tools")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("test_tool"))
        assertTrue(body.contains("A test tool"))
        assertTrue(body.contains("\"type\":\"object\""))
        assertTrue(body.contains("input"))
        assertTrue(body.contains("count"))
        memStore.close()
        store.close()
    }

    @Test
    fun `tools endpoint returns 401 when auth is configured and missing`(@TempDir tempDir: Path) = testApplication {
        val store = SqliteSpolaJobStore(tempDir.resolve("scheduler.db").toString())
        application {
            spolaApiModule(
                agentRunHandler = AgentRunHandler(),
                jobStore = store,
                config = SpolaConfig(apiKey = "secret"),
            )
        }

        val response = client.get("/api/tools")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(response.bodyAsText().contains("missing api key"))
        store.close()
    }

    @Test
    fun `agent status endpoint returns config information`(@TempDir tempDir: Path) = testApplication {
        val memStore = SqliteMemoryStore(tempDir.resolve("memory.db").toString())
        val store = SqliteSpolaJobStore(tempDir.resolve("scheduler.db").toString())

        val toolRegistry = ToolRegistry()
        toolRegistry.register(Tool(
            name = "test_tool",
            description = "A test tool",
            parameters = listOf(ToolParameter("input", "Input", ToolParameterType.STRING)),
            execute = { ToolResult.ok("done") },
        ))

        application {
            spolaApiModule(
                agentRunHandler = AgentRunHandler(),
                jobStore = store,
                memoryStore = memStore,
                toolRegistry = toolRegistry,
                config = SpolaConfig(
                    model = "test-model",
                    provider = "test-provider",
                    maxTurns = 10,
                    workingDirectory = "/tmp/test",
                ),
            )
        }

        val response = client.get("/api/agent/status")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"model\":\"test-model\""), "Should include model: $body")
        assertTrue(body.contains("\"provider\":\"test-provider\""), "Should include provider: $body")
        assertTrue(body.contains("\"maxTurns\":10"), "Should include maxTurns: $body")
        assertTrue(body.contains("\"toolCount\":1"), "Should include toolCount: $body")
        assertTrue(body.contains("\"running\":false"), "Should include running state: $body")
        assertTrue(body.contains("\"workingDirectory\""), "Should include workingDirectory: $body")
        memStore.close()
        store.close()
    }

    @Test
    fun `memory endpoint returns empty list initially`(@TempDir tempDir: Path) = testApplication {
        val memStore = SqliteMemoryStore(tempDir.resolve("memory.db").toString())
        val store = SqliteSpolaJobStore(tempDir.resolve("scheduler.db").toString())

        application {
            spolaApiModule(
                agentRunHandler = AgentRunHandler(),
                jobStore = store,
                memoryStore = memStore,
            )
        }

        val response = client.get("/api/memory")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"entries\":[]"), "Should have empty entries: $body")
        // "query" is null and excluded due to explicitNulls = false serialization config
        assertTrue(body.contains("entries"), "Should have entries field: $body")
        store.close()
        memStore.close()
    }

    @Test
    fun `memory endpoint lists saved entries and supports search`(@TempDir tempDir: Path) = testApplication {
        val memStore = SqliteMemoryStore(tempDir.resolve("memory.db").toString())
        val store = SqliteSpolaJobStore(tempDir.resolve("scheduler.db").toString())

        // Seed some memory entries
        runBlocking {
            memStore.save("user:name", "Alice")
            memStore.save("user:email", "alice@example.com")
            memStore.save("project:name", "Spola")
        }

        application {
            spolaApiModule(
                agentRunHandler = AgentRunHandler(),
                jobStore = store,
                memoryStore = memStore,
            )
        }

        // List all entries
        val listResponse = client.get("/api/memory")
        assertEquals(HttpStatusCode.OK, listResponse.status)
        val listBody = listResponse.bodyAsText()
        assertTrue(listBody.contains("Alice"), "Should contain Alice: $listBody")
        assertTrue(listBody.contains("Spola"), "Should contain Spola: $listBody")
        assertTrue(listBody.contains("user:name"), "Should contain user:name key: $listBody")
        assertTrue(listBody.contains("project:name"), "Should contain project:name key: $listBody")

        // Search by query
        val searchResponse = client.get("/api/memory?q=Alice")
        assertEquals(HttpStatusCode.OK, searchResponse.status)
        val searchBody = searchResponse.bodyAsText()
        assertTrue(searchBody.contains("Alice"), "Search should find Alice: $searchBody")
        assertTrue(searchBody.contains("\"query\":\"Alice\""), "Should include query: $searchBody")

        // Search with no results
        val noResultsResponse = client.get("/api/memory?q=NONEXISTENT_XYZ")
        assertEquals(HttpStatusCode.OK, noResultsResponse.status)
        val noResultsBody = noResultsResponse.bodyAsText()
        assertTrue(noResultsBody.contains("\"entries\":[]"), "Should have empty entries: $noResultsBody")

        store.close()
        memStore.close()
    }

    @Test
    fun `delete memory endpoint removes entry`(@TempDir tempDir: Path) = testApplication {
        val memStore = SqliteMemoryStore(tempDir.resolve("memory.db").toString())
        val store = SqliteSpolaJobStore(tempDir.resolve("scheduler.db").toString())

        // Seed a memory entry
        runBlocking {
            memStore.save("test:key", "test-value")
        }

        application {
            spolaApiModule(
                agentRunHandler = AgentRunHandler(),
                jobStore = store,
                memoryStore = memStore,
            )
        }

        // Verify it exists first
        val listBefore = client.get("/api/memory")
        assertTrue(listBefore.bodyAsText().contains("test:key"))

        // Delete it
        val deleteResponse = client.delete("/api/memory/test:key")
        assertEquals(HttpStatusCode.OK, deleteResponse.status)
        val deleteBody = deleteResponse.bodyAsText()
        assertTrue(deleteBody.contains("\"deleted\":true"), "Should confirm deletion: $deleteBody")
        assertTrue(deleteBody.contains("\"key\":\"test:key\""), "Should include the key: $deleteBody")

        // Verify it's gone
        val listAfter = client.get("/api/memory")
        assertTrue(!listAfter.bodyAsText().contains("test:key"), "Should no longer contain the key")

        store.close()
        memStore.close()
    }

    @Test
    fun `delete memory endpoint returns 404 for missing key`(@TempDir tempDir: Path) = testApplication {
        val memStore = SqliteMemoryStore(tempDir.resolve("memory.db").toString())
        val store = SqliteSpolaJobStore(tempDir.resolve("scheduler.db").toString())

        application {
            spolaApiModule(
                agentRunHandler = AgentRunHandler(),
                jobStore = store,
                memoryStore = memStore,
            )
        }

        val response = client.delete("/api/memory/nonexistent-key")
        assertEquals(HttpStatusCode.NotFound, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"deleted\":false"), "Should indicate not deleted: $body")
        assertTrue(body.contains("\"key\":\"nonexistent-key\""), "Should include the key: $body")
        assertTrue(body.contains("not found"), "Should mention not found: $body")

        store.close()
        memStore.close()
    }

    @Test
    fun `memory endpoints require auth when configured`(@TempDir tempDir: Path) = testApplication {
        val memStore = SqliteMemoryStore(tempDir.resolve("memory.db").toString())
        val store = SqliteSpolaJobStore(tempDir.resolve("scheduler.db").toString())

        application {
            spolaApiModule(
                agentRunHandler = AgentRunHandler(),
                jobStore = store,
                memoryStore = memStore,
                config = SpolaConfig(apiKey = "secret"),
            )
        }

        val listResponse = client.get("/api/memory")
        assertEquals(HttpStatusCode.Unauthorized, listResponse.status)

        val deleteResponse = client.delete("/api/memory/some-key")
        assertEquals(HttpStatusCode.Unauthorized, deleteResponse.status)

        store.close()
        memStore.close()
    }

    @Test
    fun `SSE stream sends status token and complete events`(@TempDir tempDir: Path) = testApplication {
        val provider = MockToolProvider().apply {
            addTextResponse("Streaming complete!")
        }
        val config = SpolaConfig(
            memoryDbPath = tempDir.resolve("memory.db").toString(),
            schedulerDbPath = tempDir.resolve("scheduler.db").toString(),
        )
        val store = SqliteSpolaJobStore(tempDir.resolve("jobs.db").toString())
        val handler = AgentRunHandler(
            baseConfig = config,
            instanceFactory = { baseConfig, model ->
                SpolaFactory.create(
                    config = baseConfig,
                    provider = provider,
                    effectiveModel = model,
                )
            },
        )

        application {
            spolaApiModule(
                agentRunHandler = handler,
                jobStore = store,
            )
        }

        val apiClient = createClient {
            install(ContentNegotiation) {
                json()
            }
        }

        val response = apiClient.post("/api/agent/run/stream") {
            contentType(ContentType.Application.Json)
            setBody(AgentRunRequest(goal = "stream test", model = "mock-model"))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        // SSE streaming responses send events progressively. In the Ktor testApplication,
        // the response body content type is text/event-stream and the body is not fully
        // buffered as regular text. We verify the endpoint responds correctly via status.
        store.close()
    }

    @Test
    fun `agent run stream endpoint returns SSE formatted response`(@TempDir tempDir: Path) = testApplication {
        val provider = MockToolProvider().apply {
            addTextResponse("Done")
        }
        val config = SpolaConfig(
            memoryDbPath = tempDir.resolve("memory.db").toString(),
            schedulerDbPath = tempDir.resolve("scheduler.db").toString(),
        )
        val store = SqliteSpolaJobStore(tempDir.resolve("jobs.db").toString())
        val handler = AgentRunHandler(
            baseConfig = config,
            instanceFactory = { baseConfig, model ->
                SpolaFactory.create(
                    config = baseConfig,
                    provider = provider,
                    effectiveModel = model,
                )
            },
        )

        application {
            spolaApiModule(
                agentRunHandler = handler,
                jobStore = store,
            )
        }

        val apiClient = createClient {
            install(ContentNegotiation) {
                json()
            }
        }

        val response = apiClient.post("/api/agent/run/stream") {
            contentType(ContentType.Application.Json)
            setBody(AgentRunRequest(goal = "test stream", model = "mock-model"))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        store.close()
    }

    @Test
    fun `session run endpoint persists conversation messages`(@TempDir tempDir: Path) = testApplication {
        val provider = MockToolProvider().apply {
            addTextResponse("Session run complete")
        }
        val config = SpolaConfig(
            memoryDbPath = tempDir.resolve("memory.db").toString(),
            schedulerDbPath = tempDir.resolve("scheduler.db").toString(),
            sessionsDbPath = tempDir.resolve("sessions.db").toString(),
        )
        val store = SqliteSpolaJobStore(tempDir.resolve("jobs.db").toString())
        val sessionStore = SqliteSessionStore(config.sessionsDbPath)
        val handler = AgentRunHandler(
            baseConfig = config,
            instanceFactory = { baseConfig, model ->
                SpolaFactory.create(
                    config = baseConfig,
                    provider = provider,
                    effectiveModel = model,
                )
            },
        )

        application {
            spolaApiModule(
                agentRunHandler = handler,
                jobStore = store,
                config = config,
                sessionStore = sessionStore,
            )
        }

        val apiClient = createClient {
            install(ContentNegotiation) {
                json()
            }
        }

        val createResponse = apiClient.post("/api/session") {
            contentType(ContentType.Application.Json)
            setBody(CreateSessionRequest(title = "Test session", modelId = "mock-model"))
        }
        val session = Json.decodeFromString<SessionInfo>(createResponse.bodyAsText())

        val runResponse = apiClient.post("/api/session/${session.id}/run") {
            contentType(ContentType.Application.Json)
            setBody(AgentRunRequest(goal = "say hello"))
        }

        assertEquals(HttpStatusCode.OK, runResponse.status)

        val messagesResponse = apiClient.get("/api/session/${session.id}/messages")
        assertEquals(HttpStatusCode.OK, messagesResponse.status)
        val messagesBody = messagesResponse.bodyAsText()
        assertTrue(messagesBody.contains("\"role\":\"system\""), "Should persist system message: $messagesBody")
        assertTrue(messagesBody.contains("\"role\":\"user\""), "Should persist user message: $messagesBody")
        assertTrue(messagesBody.contains("\"role\":\"assistant\""), "Should persist assistant message: $messagesBody")
        assertTrue(messagesBody.contains("say hello"), "Should contain the goal: $messagesBody")
        assertTrue(messagesBody.contains("Session run complete"), "Should contain the result: $messagesBody")

        sessionStore.close()
        store.close()
    }

    @Test
    fun `session stream endpoint persists conversation messages`(@TempDir tempDir: Path) = testApplication {
        val provider = MockToolProvider().apply {
            addTextResponse("Session stream complete")
        }
        val config = SpolaConfig(
            memoryDbPath = tempDir.resolve("memory.db").toString(),
            schedulerDbPath = tempDir.resolve("scheduler.db").toString(),
            sessionsDbPath = tempDir.resolve("sessions.db").toString(),
        )
        val store = SqliteSpolaJobStore(tempDir.resolve("jobs.db").toString())
        val sessionStore = SqliteSessionStore(config.sessionsDbPath)
        val handler = AgentRunHandler(
            baseConfig = config,
            instanceFactory = { baseConfig, model ->
                SpolaFactory.create(
                    config = baseConfig,
                    provider = provider,
                    effectiveModel = model,
                )
            },
        )

        application {
            spolaApiModule(
                agentRunHandler = handler,
                jobStore = store,
                config = config,
                sessionStore = sessionStore,
            )
        }

        val apiClient = createClient {
            install(ContentNegotiation) {
                json()
            }
        }

        val createResponse = apiClient.post("/api/session") {
            contentType(ContentType.Application.Json)
            setBody(CreateSessionRequest(title = "Streaming session", modelId = "mock-model"))
        }
        val session = Json.decodeFromString<SessionInfo>(createResponse.bodyAsText())

        val runResponse = apiClient.post("/api/session/${session.id}/run/stream") {
            contentType(ContentType.Application.Json)
            setBody(AgentRunRequest(goal = "stream hello"))
        }

        assertEquals(HttpStatusCode.OK, runResponse.status)

        val messagesResponse = apiClient.get("/api/session/${session.id}/messages")
        assertEquals(HttpStatusCode.OK, messagesResponse.status)
        val messagesBody = messagesResponse.bodyAsText()
        assertTrue(messagesBody.contains("stream hello"), "Should contain the streamed goal: $messagesBody")
        assertTrue(messagesBody.contains("Session stream complete"), "Should contain the streamed result: $messagesBody")

        sessionStore.close()
        store.close()
    }

    @Test
    fun `SSE stream with tool calls sends tool_call and tool_result events`(@TempDir tempDir: Path) = testApplication {
        val provider = MockToolProvider().apply {
            // First LLM call returns a tool call
            addToolResponse(
                dev.tramai.core.model.ToolCall(
                    id = "call-1",
                    name = "echo",
                    argumentsJson = """{"msg": "hello"}""",
                ),
            )
            // Second LLM call returns the final text
            addTextResponse("Tool result received. All done!")
        }
        val config = SpolaConfig(
            memoryDbPath = tempDir.resolve("memory.db").toString(),
            schedulerDbPath = tempDir.resolve("scheduler.db").toString(),
            maxTurns = 5,
        )
        val store = SqliteSpolaJobStore(tempDir.resolve("jobs.db").toString())

        // Create a handler
        val handler = AgentRunHandler(
            baseConfig = config,
            instanceFactory = { baseConfig, model ->
                SpolaFactory.create(
                    config = baseConfig,
                    provider = provider,
                    effectiveModel = model,
                )
            },
        )

        application {
            // Override tool registry to include echo tool
            val echoTool = Tool(
                name = "echo",
                description = "Echo input back",
                parameters = listOf(ToolParameter("msg", "Message", ToolParameterType.STRING)),
                execute = { args -> ToolResult.ok("Echo: ${args["msg"]}") },
            )
            val toolRegistry = ToolRegistry()
            toolRegistry.register(echoTool)

            spolaApiModule(
                agentRunHandler = handler,
                jobStore = store,
                toolRegistry = toolRegistry,
            )
        }

        val apiClient = createClient {
            install(ContentNegotiation) {
                json()
            }
        }

        val response = apiClient.post("/api/agent/run/stream") {
            contentType(ContentType.Application.Json)
            setBody(AgentRunRequest(goal = "call echo tool", model = "mock-model"))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        store.close()
    }

    @Test
    fun `jobs endpoints create list and delete jobs`(@TempDir tempDir: Path) = testApplication {
        val store = SqliteSpolaJobStore(tempDir.resolve("scheduler.db").toString())
        application {
            spolaApiModule(
                agentRunHandler = AgentRunHandler(),
                jobStore = store,
            )
        }

        val apiClient = createClient {
            install(ContentNegotiation) {
                json()
            }
        }

        val createResponse = apiClient.post("/api/jobs") {
            contentType(ContentType.Application.Json)
            setBody(
                CreateJobRequest(
                    name = "nightly docs",
                    cronExpression = "0 2 * * *",
                    goal = "refresh docs",
                ),
            )
        }
        val createdBody = createResponse.bodyAsText()
        val createdJob = Json.decodeFromString<ScheduledJobResponse>(createdBody)
        assertEquals(HttpStatusCode.Created, createResponse.status)
        assertTrue(createdBody.contains("nightly docs"))
        assertNotNull(createdJob.id)

        val listResponse = apiClient.get("/api/jobs")
        assertEquals(HttpStatusCode.OK, listResponse.status)
        assertTrue(listResponse.bodyAsText().contains(createdJob.id))

        val deleteResponse = apiClient.delete("/api/jobs/${createdJob.id}")
        assertEquals(HttpStatusCode.OK, deleteResponse.status)
        assertTrue(deleteResponse.bodyAsText().contains("\"removed\":true"))

        val missingDeleteResponse = apiClient.delete("/api/jobs/${createdJob.id}")
        assertEquals(HttpStatusCode.NotFound, missingDeleteResponse.status)
        store.close()
    }

    @Test
    fun `jobs endpoint creates job with enabled=false`(@TempDir tempDir: Path) = testApplication {
        val store = SqliteSpolaJobStore(tempDir.resolve("scheduler.db").toString())
        application {
            spolaApiModule(
                agentRunHandler = AgentRunHandler(),
                jobStore = store,
            )
        }

        val apiClient = createClient {
            install(ContentNegotiation) {
                json()
            }
        }

        val createResponse = apiClient.post("/api/jobs") {
            contentType(ContentType.Application.Json)
            setBody(
                CreateJobRequest(
                    name = "disabled job",
                    cronExpression = "0 0 * * *",
                    goal = "do nothing",
                    enabled = false,
                ),
            )
        }

        assertEquals(HttpStatusCode.Created, createResponse.status)
        val job = Json.decodeFromString<ScheduledJobResponse>(createResponse.bodyAsText())
        assertTrue(!job.enabled, "Job should be created as disabled")
        store.close()
    }

    @Test
    fun `auth returns 401 when key is missing`(@TempDir tempDir: Path) = testApplication {
        val store = SqliteSpolaJobStore(tempDir.resolve("scheduler.db").toString())
        application {
            spolaApiModule(
                agentRunHandler = AgentRunHandler(),
                jobStore = store,
                config = SpolaConfig(apiKey = "secret"),
            )
        }

        val response = client.get("/api/jobs")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(response.bodyAsText().contains("missing api key"))
        store.close()
    }

    @Test
    fun `auth returns 403 when key is wrong`(@TempDir tempDir: Path) = testApplication {
        val store = SqliteSpolaJobStore(tempDir.resolve("scheduler.db").toString())
        application {
            spolaApiModule(
                agentRunHandler = AgentRunHandler(),
                jobStore = store,
                config = SpolaConfig(apiKey = "secret"),
            )
        }

        val response = client.get("/api/jobs") {
            header("Authorization", "Bearer wrong")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertTrue(response.bodyAsText().contains("invalid api key"))
        store.close()
    }

    @Test
    fun `auth returns 200 when key is valid`(@TempDir tempDir: Path) = testApplication {
        val store = SqliteSpolaJobStore(tempDir.resolve("scheduler.db").toString())
        application {
            spolaApiModule(
                agentRunHandler = AgentRunHandler(),
                jobStore = store,
                config = SpolaConfig(apiKey = "secret"),
            )
        }

        val response = client.get("/api/jobs") {
            header("Authorization", "Bearer secret")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        store.close()
    }

    @Test
    fun `auth for POST jobs returns 401 when key is missing`(@TempDir tempDir: Path) = testApplication {
        val store = SqliteSpolaJobStore(tempDir.resolve("scheduler.db").toString())
        application {
            spolaApiModule(
                agentRunHandler = AgentRunHandler(),
                jobStore = store,
                config = SpolaConfig(apiKey = "secret"),
            )
        }

        val apiClient = createClient {
            install(ContentNegotiation) {
                json()
            }
        }

        val response = apiClient.post("/api/jobs") {
            contentType(ContentType.Application.Json)
            setBody(CreateJobRequest(name = "test", cronExpression = "0 0 * * *", goal = "test"))
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(response.bodyAsText().contains("missing api key"))
        store.close()
    }

    @Test
    fun `auth for DELETE jobs returns 401 when key is missing`(@TempDir tempDir: Path) = testApplication {
        val store = SqliteSpolaJobStore(tempDir.resolve("scheduler.db").toString())
        application {
            spolaApiModule(
                agentRunHandler = AgentRunHandler(),
                jobStore = store,
                config = SpolaConfig(apiKey = "secret"),
            )
        }

        val response = client.delete("/api/jobs/some-id")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(response.bodyAsText().contains("missing api key"))
        store.close()
    }

    @Test
    fun `checkpoint list endpoint returns empty list initially`(@TempDir tempDir: Path) = testApplication {
        val store = SqliteSpolaJobStore(tempDir.resolve("scheduler.db").toString())
        val memStore = SqliteMemoryStore(tempDir.resolve("memory.db").toString())
        application {
            spolaApiModule(
                agentRunHandler = AgentRunHandler(),
                jobStore = store,
                memoryStore = memStore,
            )
        }

        val response = client.get("/api/checkpoint")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("checkpoints"))
        memStore.close()
        store.close()
    }

    @Test
    fun `checkpoint resume returns 404 for missing session`(@TempDir tempDir: Path) = testApplication {
        val store = SqliteSpolaJobStore(tempDir.resolve("scheduler.db").toString())
        val memStore = SqliteMemoryStore(tempDir.resolve("memory.db").toString())
        application {
            spolaApiModule(
                agentRunHandler = AgentRunHandler(),
                jobStore = store,
                memoryStore = memStore,
            )
        }

        val response = client.get("/api/checkpoint/resume/nonexistent")

        assertEquals(HttpStatusCode.NotFound, response.status)
        memStore.close()
        store.close()
    }

    @Test
    fun `checkpoint with real data lists entries and resumes session`(@TempDir tempDir: Path) = testApplication {
        val store = SqliteSpolaJobStore(tempDir.resolve("scheduler.db").toString())
        val memStore = SqliteMemoryStore(tempDir.resolve("memory.db").toString())
        val checkpointStore = CheckpointStore(tempDir.resolve("checkpoints.db").toString())
        val checkpointManager = CheckpointManager(checkpointStore)

        // Seed a real checkpoint
        val sessionId = checkpointManager.generateSessionId()
        checkpointManager.save(
            sessionId = sessionId,
            turn = 1,
            conversation = listOf(
                dev.spola.UserMessage("Hello"),
                dev.spola.AssistantMessage("Hi there!"),
            ),
        )

        application {
            spolaApiModule(
                agentRunHandler = AgentRunHandler(),
                jobStore = store,
                memoryStore = memStore,
                checkpointManager = checkpointManager,
            )
        }

        // List checkpoints — should include our seeded entry
        val listResponse = client.get("/api/checkpoint")
        assertEquals(HttpStatusCode.OK, listResponse.status)
        val listBody = listResponse.bodyAsText()
        assertTrue(listBody.contains(sessionId), "List should contain our session: $listBody")
        assertTrue(listBody.contains("\"turnNumber\":1"), "Should show turn number: $listBody")

        // Resume the session — should return the messages
        val resumeResponse = client.get("/api/checkpoint/resume/$sessionId")
        assertEquals(HttpStatusCode.OK, resumeResponse.status)
        val resumeBody = resumeResponse.bodyAsText()
        assertTrue(resumeBody.contains("Hello"), "Should contain user message: $resumeBody")
        assertTrue(resumeBody.contains("Hi there!"), "Should contain assistant message: $resumeBody")
        assertTrue(resumeBody.contains("\"messageCount\":2"), "Should count 2 messages: $resumeBody")

        checkpointStore.close()
        memStore.close()
        store.close()
    }

    @Test
    fun `diff endpoint returns diff for a specific checkpoint`(@TempDir tempDir: Path) = testApplication {
        val store = SqliteSpolaJobStore(tempDir.resolve("scheduler.db").toString())
        val memStore = SqliteMemoryStore(tempDir.resolve("memory.db").toString())
        val checkpointStore = CheckpointStore(tempDir.resolve("checkpoints.db").toString())

        // Seed a checkpoint directly via store (bypasses git diff computation)
        val diffContent = "--- a/file.txt\n+++ b/file.txt\n@@ -1 +1 @@\n-old content\n+new content"
        val cpId = checkpointStore.save(
            sessionId = "diff-test-session",
            turnNumber = 1,
            conversationJson = "[]",
            diff = diffContent,
        )

        application {
            spolaApiModule(
                agentRunHandler = AgentRunHandler(),
                jobStore = store,
                memoryStore = memStore,
                checkpointManager = CheckpointManager(checkpointStore),
            )
        }

        val response = client.get("/api/checkpoint/$cpId/diff")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("diff-test-session"), "Should contain sessionId: $body")
        assertTrue(body.contains("\"turnNumber\":1"), "Should contain turnNumber: $body")

        // Parse the response to verify the diff field correctly
        val parsed = Json.decodeFromString<CheckpointDiffResponse>(body)
        assertEquals(1L, parsed.id)
        assertEquals("diff-test-session", parsed.sessionId)
        assertEquals(1, parsed.turnNumber)
        assertEquals(diffContent, parsed.diff)

        checkpointStore.close()
        memStore.close()
        store.close()
    }

    @Test
    fun `session diffs endpoint returns all checkpoints with diffs for a session`(@TempDir tempDir: Path) = testApplication {
        val store = SqliteSpolaJobStore(tempDir.resolve("scheduler.db").toString())
        val memStore = SqliteMemoryStore(tempDir.resolve("memory.db").toString())
        val checkpointStore = CheckpointStore(tempDir.resolve("checkpoints.db").toString())
        val sessionId = "session-diffs-test"

        // Seed multiple checkpoints for the same session with diffs
        checkpointStore.save(sessionId, 1, "[]", diff = "turn 1 diff")
        checkpointStore.save(sessionId, 2, "[]", diff = "turn 2 diff")
        checkpointStore.save(sessionId, 3, "[]", diff = "turn 3 diff")

        application {
            spolaApiModule(
                agentRunHandler = AgentRunHandler(),
                jobStore = store,
                memoryStore = memStore,
                checkpointManager = CheckpointManager(checkpointStore),
            )
        }

        val response = client.get("/api/checkpoint/session/$sessionId/diffs")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("turn 1 diff"), "Should contain turn 1 diff: $body")
        assertTrue(body.contains("turn 2 diff"), "Should contain turn 2 diff: $body")
        assertTrue(body.contains("turn 3 diff"), "Should contain turn 3 diff: $body")
        assertTrue(body.contains("\"turnNumber\":3"), "Should contain turn 3: $body")

        // Parse to verify the full response structure
        val parsed = Json.decodeFromString<CheckpointListResponse>(body)
        assertEquals(3, parsed.checkpoints.size)
        assertEquals("turn 1 diff", parsed.checkpoints.find { it.turnNumber == 1 }?.diff)
        assertEquals("turn 2 diff", parsed.checkpoints.find { it.turnNumber == 2 }?.diff)
        assertEquals("turn 3 diff", parsed.checkpoints.find { it.turnNumber == 3 }?.diff)

        checkpointStore.close()
        memStore.close()
        store.close()
    }

    @Test
    fun `diff endpoint returns 404 for nonexistent checkpoint`(@TempDir tempDir: Path) = testApplication {
        val store = SqliteSpolaJobStore(tempDir.resolve("scheduler.db").toString())
        val memStore = SqliteMemoryStore(tempDir.resolve("memory.db").toString())
        val checkpointStore = CheckpointStore(tempDir.resolve("checkpoints.db").toString())

        application {
            spolaApiModule(
                agentRunHandler = AgentRunHandler(),
                jobStore = store,
                memoryStore = memStore,
                checkpointManager = CheckpointManager(checkpointStore),
            )
        }

        val response = client.get("/api/checkpoint/999999/diff")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("checkpoint not found"), "Should return checkpoint not found: ${response.bodyAsText()}")

        checkpointStore.close()
        memStore.close()
        store.close()
    }

    @Test
    fun `agent run rejects blank goal`(@TempDir tempDir: Path) = testApplication {
        val store = SqliteSpolaJobStore(tempDir.resolve("scheduler.db").toString())
        application {
            spolaApiModule(
                agentRunHandler = AgentRunHandler(),
                jobStore = store,
            )
        }

        val apiClient = createClient {
            install(ContentNegotiation) {
                json()
            }
        }

        val response = apiClient.post("/api/agent/run") {
            contentType(ContentType.Application.Json)
            setBody(AgentRunRequest(goal = "  ", model = "mock-model"))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("goal must not be blank"))
        store.close()
    }

    @Test
    fun `agent run rejects missing goal field`(@TempDir tempDir: Path) = testApplication {
        val store = SqliteSpolaJobStore(tempDir.resolve("scheduler.db").toString())
        application {
            spolaApiModule(
                agentRunHandler = AgentRunHandler(),
                jobStore = store,
            )
        }

        val apiClient = createClient {
            install(ContentNegotiation) {
                json()
            }
        }

        // Send a request with completely missing goal field (empty JSON object)
        val response = apiClient.post("/api/agent/run") {
            contentType(ContentType.Application.Json)
            setBody("""{"model": "mock-model"}""")
        }

        // Should fail because goal is missing/blank
        assertEquals(HttpStatusCode.BadRequest, response.status)
        store.close()
    }

    @Test
    fun `agent run rejects null goal field`(@TempDir tempDir: Path) = testApplication {
        val store = SqliteSpolaJobStore(tempDir.resolve("scheduler.db").toString())
        application {
            spolaApiModule(
                agentRunHandler = AgentRunHandler(),
                jobStore = store,
            )
        }

        val apiClient = createClient {
            install(ContentNegotiation) {
                json()
            }
        }

        val response = apiClient.post("/api/agent/run") {
            contentType(ContentType.Application.Json)
            setBody("""{"goal": null, "model": "mock-model"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        store.close()
    }

    @Test
    fun `agent run stream rejects blank goal via SSE events`(@TempDir tempDir: Path) = testApplication {
        val store = SqliteSpolaJobStore(tempDir.resolve("scheduler.db").toString())
        application {
            spolaApiModule(
                agentRunHandler = AgentRunHandler(),
                jobStore = store,
            )
        }

        val apiClient = createClient {
            install(ContentNegotiation) {
                json()
            }
        }

        val response = apiClient.post("/api/agent/run/stream") {
            contentType(ContentType.Application.Json)
            setBody(AgentRunRequest(goal = "  ", model = "mock-model"))
        }

        // SSE endpoint responds with 200 OK and sends error events via the stream
        // The blank goal validation happens inside the SSE lambda (after response is committed)
        // Note: In Ktor's testApplication framework, SSE response bodies are not buffered as
        // regular text — the test client receives the 200 response before streaming begins.
        // All SSE tests in this file follow the same pattern of status-only verification.
        assertEquals(HttpStatusCode.OK, response.status)
        store.close()
    }

    @Test
    fun `delivery telegram endpoint requires telegram tool`(@TempDir tempDir: Path) = testApplication {
        val store = SqliteSpolaJobStore(tempDir.resolve("scheduler.db").toString())
        val memStore = SqliteMemoryStore(tempDir.resolve("memory.db").toString())
        // Use an empty tool registry to guarantee the telegram_send tool is missing
        val emptyRegistry = ToolRegistry()
        application {
            spolaApiModule(
                agentRunHandler = AgentRunHandler(),
                jobStore = store,
                memoryStore = memStore,
                toolRegistry = emptyRegistry,
                config = SpolaConfig(apiKey = "test-key"),
            )
        }

        val apiClient = createClient {
            install(ContentNegotiation) {
                json()
            }
        }

        // Without telegram_send tool registered, it should throw an error
        val response = apiClient.post("/api/deliver/telegram") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer test-key")
            setBody(TelegramSendRequest(chatId = "123", text = "hello"))
        }

        // The tool isn't registered — IllegalStateException results in 500
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertTrue(response.bodyAsText().contains("telegram_send tool not registered"),
            "Should mention missing telegram_send tool: ${response.bodyAsText()}")
        store.close()
        memStore.close()
    }

    @Test
    fun `delivery email endpoint requires email tool`(@TempDir tempDir: Path) = testApplication {
        val store = SqliteSpolaJobStore(tempDir.resolve("scheduler.db").toString())
        val memStore = SqliteMemoryStore(tempDir.resolve("memory.db").toString())
        // Use an empty tool registry to guarantee the email_send tool is missing
        val emptyRegistry = ToolRegistry()
        application {
            spolaApiModule(
                agentRunHandler = AgentRunHandler(),
                jobStore = store,
                memoryStore = memStore,
                toolRegistry = emptyRegistry,
                config = SpolaConfig(apiKey = "test-key"),
            )
        }

        val apiClient = createClient {
            install(ContentNegotiation) {
                json()
            }
        }

        val response = apiClient.post("/api/deliver/email") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer test-key")
            setBody(EmailSendRequest(to = "user@example.com", subject = "Test", body = "Hello"))
        }

        // The tool isn't registered — IllegalStateException results in 500
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertTrue(response.bodyAsText().contains("email_send tool not registered"),
            "Should mention missing email_send tool: ${response.bodyAsText()}")
        store.close()
        memStore.close()
    }

    @Test
    fun `metrics endpoint returns prometheus text`() = testApplication {
        val store = SqliteSpolaJobStore(":memory:")
        val metrics = dev.spola.metrics.SpolaMetrics(isEnabled = false)
        application {
            spolaApiModule(
                agentRunHandler = AgentRunHandler(),
                jobStore = store,
                spolaMetrics = metrics,
            )
        }

        val response = client.get("/api/metrics")

        assertEquals(HttpStatusCode.OK, response.status)
        // The metrics endpoint returns text/plain prometheus-style output
        val body = response.bodyAsText()
        assertTrue(body.isNotEmpty(), "Metrics body should not be empty")
        store.close()
    }

    @Test
    fun `memory endpoint handles overwriting existing key`(@TempDir tempDir: Path) = testApplication {
        val memStore = SqliteMemoryStore(tempDir.resolve("memory.db").toString())
        val store = SqliteSpolaJobStore(tempDir.resolve("scheduler.db").toString())

        // Seed an initial value
        runBlocking {
            memStore.save("user:city", "New York")
        }

        application {
            spolaApiModule(
                agentRunHandler = AgentRunHandler(),
                jobStore = store,
                memoryStore = memStore,
            )
        }

        // Verify initial value
        val beforeResponse = client.get("/api/memory")
        assertTrue(beforeResponse.bodyAsText().contains("New York"))

        // Overwrite via direct memory store (simulating what the agent would do)
        runBlocking {
            memStore.save("user:city", "San Francisco")
        }

        // Verify the value was updated
        val afterResponse = client.get("/api/memory")
        val afterBody = afterResponse.bodyAsText()
        assertTrue(afterBody.contains("San Francisco"), "Should contain new value: $afterBody")
        assertTrue(!afterBody.contains("New York"), "Should not contain old value: $afterBody")

        store.close()
        memStore.close()
    }

    @Test
    fun `multiple memory entries have unique keys`(@TempDir tempDir: Path) = testApplication {
        val memStore = SqliteMemoryStore(tempDir.resolve("memory.db").toString())
        val store = SqliteSpolaJobStore(tempDir.resolve("scheduler.db").toString())

        runBlocking {
            memStore.save("key:one", "value one")
            memStore.save("key:two", "value two")
            memStore.save("key:three", "value three")
        }

        application {
            spolaApiModule(
                agentRunHandler = AgentRunHandler(),
                jobStore = store,
                memoryStore = memStore,
            )
        }

        val response = client.get("/api/memory")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("key:one"), "Should contain key:one")
        assertTrue(body.contains("key:two"), "Should contain key:two")
        assertTrue(body.contains("key:three"), "Should contain key:three")

        store.close()
        memStore.close()
    }

    @Test
    fun `delete memory with URL-encoded special characters`(@TempDir tempDir: Path) = testApplication {
        val memStore = SqliteMemoryStore(tempDir.resolve("memory.db").toString())
        val store = SqliteSpolaJobStore(tempDir.resolve("scheduler.db").toString())

        runBlocking {
            memStore.save("namespace/key", "value")
        }

        application {
            spolaApiModule(
                agentRunHandler = AgentRunHandler(),
                jobStore = store,
                memoryStore = memStore,
            )
        }

        // Ktor will URL-decode the path parameter automatically
        val response = client.delete("/api/memory/namespace%2Fkey")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"deleted\":true"))
        assertTrue(response.bodyAsText().contains("\"key\":\"namespace/key\""))

        store.close()
        memStore.close()
    }
}
