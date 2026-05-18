package dev.spola.api

import dev.spola.GolemConfig
import dev.spola.kanban.SqliteKanbanStore
import dev.spola.scheduler.SqliteGolemJobStore
import dev.spola.workflow.NewWorkflowExecution
import dev.spola.workflow.SqliteWorkflowExecutionStore
import dev.spola.workflow.WorkflowExecutionService
import dev.spola.workflow.WorkflowKanbanService
import dev.spola.workflow.WorkflowTemplateRegistry
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkflowTriggerApiTest {

    @Test
    fun `scheduler execution history endpoint lists executions for a workflow job`(@TempDir tempDir: Path) = testApplication {
        val config = testConfig(tempDir)
        val jobStore = SqliteGolemJobStore(config.schedulerDbPath)
        val workflowStore = SqliteWorkflowExecutionStore(config.workflowDbPath)

        application {
            golemApiModule(
                config = config,
                agentRunHandler = AgentRunHandler(baseConfig = config),
                jobStore = jobStore,
                workflowExecutionStore = workflowStore,
            )
        }

        val apiClient = createClient {
            install(ContentNegotiation) {
                json()
            }
        }

        val createResponse = apiClient.post("/api/jobs") {
            header("Authorization", "Bearer ${config.apiKey}")
            contentType(ContentType.Application.Json)
            setBody(
                CreateJobRequest(
                    name = "nightly review",
                    cronExpression = "0 2 * * *",
                    goal = "review nightly changes",
                    workflowDefinitionId = "nightly-review",
                ),
            )
        }
        val createdJob = Json.decodeFromString<ScheduledJobResponse>(createResponse.bodyAsText())

        runBlocking {
            workflowStore.create(
                NewWorkflowExecution(
                    definitionId = "nightly-review",
                    workflowName = "nightly-review",
                    triggerSource = "scheduler",
                    triggerRef = createdJob.id,
                    inputJson = """{"goal":"review nightly changes","parametersJson":"{}"}""",
                ),
            )
        }

        val response = apiClient.get("/api/scheduler/jobs/${createdJob.id}/executions") {
            header("Authorization", "Bearer ${config.apiKey}")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("nightly-review", createdJob.workflowDefinitionId)
        assertTrue(response.bodyAsText().contains(createdJob.id))
        assertTrue(response.bodyAsText().contains("\"triggerSource\":\"scheduler\""))
    }

    @Test
    fun `kanban history endpoint returns executions created by status transitions`(@TempDir tempDir: Path) = testApplication {
        val config = testConfig(tempDir)
        val jobStore = SqliteGolemJobStore(config.schedulerDbPath)
        val workflowStore = SqliteWorkflowExecutionStore(config.workflowDbPath)
        val workflowExecutionService = WorkflowExecutionService(
            config = config,
            executionStore = workflowStore,
            workflowRegistry = WorkflowTemplateRegistry(),
        )
        val workflowKanbanService = WorkflowKanbanService(
            executionService = workflowExecutionService,
            cooldownSeconds = config.kanbanWorkflowCooldownSeconds,
            currentEpochSeconds = { 100L },
        )
        val kanbanStore = SqliteKanbanStore(
            dbPath = config.kanbanDbPath,
            onStatusChanged = workflowKanbanService::onTaskStatusChanged,
        )

        application {
            golemApiModule(
                config = config,
                agentRunHandler = AgentRunHandler(baseConfig = config),
                jobStore = jobStore,
                kanbanStore = kanbanStore,
                workflowExecutionStore = workflowStore,
                workflowExecutionService = workflowExecutionService,
                workflowTemplateRegistry = WorkflowTemplateRegistry(),
            )
        }

        val apiClient = createClient {
            install(ContentNegotiation) {
                json()
            }
        }

        val created = apiClient.post("/api/kanban") {
            header("Authorization", "Bearer ${config.apiKey}")
            contentType(ContentType.Application.Json)
            setBody(KanbanCreateRequest(text = "Review PR"))
        }
        val card = Json.decodeFromString<KanbanCardResponse>(created.bodyAsText())

        val updated = apiClient.put("/api/kanban/${card.id}") {
            header("Authorization", "Bearer ${config.apiKey}")
            contentType(ContentType.Application.Json)
            setBody(KanbanUpdateRequest(text = "Review PR", status = "done"))
        }
        val history = apiClient.get("/api/kanban/tasks/${card.id}/executions") {
            header("Authorization", "Bearer ${config.apiKey}")
        }

        assertEquals(HttpStatusCode.OK, updated.status)
        assertEquals(HttpStatusCode.OK, history.status)
        assertTrue(history.bodyAsText().contains(card.id))
        assertTrue(history.bodyAsText().contains("\"triggerSource\":\"kanban\""))
        assertTrue(history.bodyAsText().contains("\"workflowName\":\"code-review\""))
    }

    private fun testConfig(tempDir: Path) = GolemConfig(
        apiKey = "secret",
        memoryDbPath = tempDir.resolve("memory.db").toString(),
        schedulerDbPath = tempDir.resolve("scheduler.db").toString(),
        sessionsDbPath = tempDir.resolve("sessions.db").toString(),
        workflowDbPath = tempDir.resolve("workflows.db").toString(),
        checkpointDbPath = tempDir.resolve("checkpoint.db").toString(),
        kanbanDbPath = tempDir.resolve("kanban.db").toString(),
        jvmIndexDbPath = tempDir.resolve("jvm-index.db").toString(),
    )
}
