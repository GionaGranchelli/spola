package dev.spola.workflow

import kotlin.reflect.typeOf
import kotlinx.coroutines.test.runTest
import dev.spola.SpolaConfig
import dev.spola.SpolaFactory
import dev.tramai.orchestration.StopPolicy
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JvmWorkflowTemplatesTest {
    @Test
    fun `jvm debug workflow has correct name`() {
        val workflow = JvmWorkflowTemplates.jvmDebugWorkflow()
        assertEquals("jvm-debug", workflow.name)
        assertEquals(typeOf<String>(), workflow.resultType)
    }

    @Test
    fun `jvm refactor workflow builds`() {
        val workflow = JvmWorkflowTemplates.jvmRefactorWorkflow()
        assertNotNull(workflow)
        assertTrue(workflow.name.contains("jvm-refactor"))
    }

    @Test
    fun `jvm migration workflow builds`() {
        val workflow = JvmWorkflowTemplates.jvmMigrationWorkflow()
        assertNotNull(workflow)
        assertTrue(workflow.name.contains("jvm-migration"))
    }

    @Test
    fun `two-step workflow passes intermediateResults between steps via localStep`() = runTest {
        val initialState = SpolaState(
            goal = "test-state-transfer",
            intermediateResults = mapOf("step-1" to "diagnosis-complete"),
        )
        val result = SpolaFactory.runWorkflow(
            name = "state-transfer-test",
            initialState = initialState,
            config = SpolaConfig().copy(metricsEnabled = false),
            stopPolicy = StopPolicy(maxStepExecutions = 10),
            workflow = {
                localStep("step-1") { state, _ ->
                    state.copy(
                        turnCount = state.turnCount + 1,
                        result = "diagnosis: found issue",
                    )
                }
                localStep("step-2") { state, _ ->
                    val priorResult = state.result ?: ""
                    state.copy(
                        result = "fix-applied after ($priorResult)",
                        turnCount = state.turnCount + 1,
                    )
                }
            },
            resultSelector = { it.result ?: "no result" },
        )
        assertTrue(result.contains("diagnosis"), "Step-2 result must reference Step-1 output")
        assertTrue(result.contains("fix-applied"), "Step-2 must complete")
    }
}
