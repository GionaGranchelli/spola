package dev.spola.workflow

import dev.spola.SpolaConfig
import dev.spola.ToolRegistry
import dev.spola.api.spolaApiModule
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkflowChatIntegrationTest {

    @Test
    fun `workflow_run tool rejects nested workflow execution`(@TempDir tempDir: Path) = runTest {
        val service = WorkflowExecutionService(
            config = SpolaConfig(maxWorkflowNestingDepth = 1),
            executionStore = SqliteWorkflowExecutionStore(tempDir.resolve("workflows.db").toString()),
            workflowRegistry = WorkflowTemplateRegistry(),
        )
        val registry = ToolRegistry()
        registerWorkflowTools(registry, service)
        val tool = registry.get("workflow_run") ?: error("workflow_run tool not registered")
        val payload = Json.encodeToString(
            WorkflowRunToolInput(
                workflowName = "nested-review",
                goal = "run nested workflow",
                sessionId = "session-1",
            ),
        )

        val result = tool.execute(mapOf("inputJson" to payload))

        assertTrue(result.success)
        assertTrue(result.output.isNotEmpty())
    }

    @Test
    fun `session executions endpoint lists executions for a session`(@TempDir tempDir: Path) = testApplication {
        val config = SpolaConfig(
            apiKey = "secret",
            memoryDbPath = tempDir.resolve("memory.db").toString(),
            schedulerDbPath = tempDir.resolve("scheduler.db").toString(),
            sessionsDbPath = tempDir.resolve("sessions.db").toString(),
            workflowDbPath = tempDir.resolve("workflows.db").toString(),
            checkpointDbPath = tempDir.resolve("checkpoint.db").toString(),
            kanbanDbPath = tempDir.resolve("kanban.db").toString(),
            jvmIndexDbPath = tempDir.resolve("jvm-index.db").toString(),
        )
        val workflowStore = SqliteWorkflowExecutionStore(config.workflowDbPath)
        val jobStore = dev.spola.scheduler.SqliteSpolaJobStore(config.schedulerDbPath)

        runBlocking {
            workflowStore.create(
                NewWorkflowExecution(
                    definitionId = null,
                    workflowName = "code-review",
                    sessionId = "session-123",
                    triggerSource = "api",
                    inputJson = """{"goal":"review","parametersJson":"{}"}""",
                ),
            )
        }

        application {
            spolaApiModule(
                config = config,
                agentRunHandler = dev.spola.api.AgentRunHandler(baseConfig = config),
                jobStore = jobStore,
                workflowExecutionStore = workflowStore,
            )
        }

        val response = client.get("/api/sessions/session-123/executions") {
            header("Authorization", "Bearer secret")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"workflowName\":\"code-review\""))
        assertTrue(body.contains("\"sessionId\":\"session-123\""))
        workflowStore.close()
        jobStore.close()
    }
}
