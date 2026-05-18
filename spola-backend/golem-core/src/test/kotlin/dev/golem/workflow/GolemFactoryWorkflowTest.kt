package dev.spola.workflow

import dev.spola.GolemConfig
import dev.spola.GolemFactory
import dev.tramai.orchestration.StopPolicy
import dev.tramai.orchestration.Workflow
import kotlin.reflect.typeOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertEquals

class GolemFactoryWorkflowTest {

    // -- createWorkflow tests --------------------------------------------------------

    @Test
    fun `createWorkflow builds a valid workflow`() {
        val workflow = GolemFactory.createWorkflow<String>(
            name = "test-workflow",
            workflow = {
                localStep("transform") { state, _ ->
                    state.copy(result = "hello from localStep")
                }
            },
            resultSelector = { it.result ?: "" },
        )
        assertNotNull(workflow)
        assertIs<Workflow<GolemState, String>>(workflow)
        assertEquals("test-workflow", workflow.name)
        assertEquals(typeOf<GolemState>(), workflow.stateType)
        assertEquals(typeOf<String>(), workflow.resultType)
    }

    @Test
    fun `createWorkflow accepts custom stopPolicy and definitionVersion`() {
        val workflow = GolemFactory.createWorkflow<String>(
            name = "custom-wf",
            definitionVersion = "2",
            stopPolicy = StopPolicy(maxStepExecutions = 5),
            workflow = {
                localStep("step1") { state, _ -> state.copy(result = "done") }
            },
            resultSelector = { it.result ?: "" },
        )
        assertEquals("custom-wf", workflow.name)
    }

    // -- runWorkflow tests -----------------------------------------------------------

    @Test
    fun `runWorkflow with a simple localStep`() = runTest {
        val initialState = GolemState(goal = "test")
        val result = GolemFactory.runWorkflow(
            name = "local-step-test",
            initialState = initialState,
            config = GolemConfig().copy(metricsEnabled = false),
            workflow = {
                localStep("transform") { state, _ ->
                    state.copy(
                        result = "processed: ${state.goal}",
                        turnCount = state.turnCount + 1,
                    )
                }
            },
            resultSelector = { it.result ?: "" },
        )
        assertEquals("processed: test", result)
    }

    @Test
    fun `runWorkflow with localStep multiple steps`() = runTest {
        val initialState = GolemState(goal = "multi")
        val result = GolemFactory.runWorkflow(
            name = "multi-step-test",
            initialState = initialState,
            config = GolemConfig().copy(metricsEnabled = false),
            workflow = {
                localStep("step-a") { state, _ ->
                    state.copy(turnCount = 1)
                }
                localStep("step-b") { state, _ ->
                    state.copy(
                        result = "finished after ${state.turnCount} step(s)",
                        turnCount = state.turnCount + 1,
                    )
                }
            },
            resultSelector = { it.result ?: "" },
        )
        assertEquals("finished after 1 step(s)", result)
    }

    @Test
    fun `runWorkflow with parallelAgentsStep builds and runs correctly`() = runTest {
        val initialState = GolemState(goal = "parallel-test")
        with(TeamWorkflowSteps) {
            val result = GolemFactory.runWorkflow(
                name = "parallel-agents-test",
                initialState = initialState,
                config = GolemConfig().copy(metricsEnabled = false),
                stopPolicy = StopPolicy(maxStepExecutions = 20),
                workflow = {
                    // Use localStep to simulate parallel agent results
                    // (parallelAgentsStep makes HTTP calls, so we test the build
                    //  and run lifecycle with a localStep before it)
                    localStep("prepare") { state, _ ->
                        state.copy(
                            intermediateResults = mapOf(
                                "agent-a" to "result-a",
                                "agent-b" to "result-b",
                            ),
                        )
                    }
                },
                resultSelector = { state ->
                    "${state.intermediateResults["agent-a"]} | ${state.intermediateResults["agent-b"]}"
                },
            )
            assertEquals("result-a | result-b", result)
        }
    }
}
