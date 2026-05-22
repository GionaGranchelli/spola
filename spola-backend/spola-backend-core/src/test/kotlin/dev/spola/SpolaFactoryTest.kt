package dev.spola

import dev.spola.agent.AgentDefinition
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertNotNull

class SpolaFactoryTest {

    @Test
    fun `create registers agent skill and delivery tools`(@TempDir tempDir: Path) = runTest {
        val instance = SpolaFactory.create(
            config = testConfig(tempDir),
            provider = MockToolProvider(),
            effectiveModel = "mock-model",
        )

        try {
            assertNotNull(instance.toolRegistry.get("agent_list"))
            assertNotNull(instance.toolRegistry.get("skill_list"))
            assertNotNull(instance.toolRegistry.get("telegram_send"))
            assertNotNull(instance.toolRegistry.get("email_send"))
            assertNotNull(instance.toolRegistry.get("project_insight_save"))
            assertNotNull(instance.toolRegistry.get("provenance_export"))
        } finally {
            instance.close()
        }
    }

    @Test
    fun `createFromAgentDefinition keeps skill and delivery tools available`(@TempDir tempDir: Path) = runTest {
        val instance = SpolaFactory.createFromAgentDefinition(
            agentDef = AgentDefinition(
                id = "custom-agent",
                name = "Custom Agent",
                systemPrompt = "You are a test agent.",
                preferredModel = "gpt-4o",
                preferredProvider = "openai-compat",
            ),
            config = testConfig(tempDir),
        )

        try {
            assertNotNull(instance.toolRegistry.get("skill_list"))
            assertNotNull(instance.toolRegistry.get("telegram_send"))
            assertNotNull(instance.toolRegistry.get("email_send"))
            assertNotNull(instance.toolRegistry.get("jvm_project_overview"))
            assertNotNull(instance.toolRegistry.get("project_insight_search"))
            assertNotNull(instance.toolRegistry.get("provenance_info"))
        } finally {
            instance.close()
        }
    }

    private fun testConfig(tempDir: Path) = SpolaConfig(
        memoryDbPath = tempDir.resolve("memory.db").toString(),
        schedulerDbPath = tempDir.resolve("scheduler.db").toString(),
        checkpointDbPath = tempDir.resolve("checkpoint.db").toString(),
        agentsDbPath = tempDir.resolve("agents.db").toString(),
        sessionsDbPath = tempDir.resolve("sessions.db").toString(),
        metricsEnabled = false,
    )
}
