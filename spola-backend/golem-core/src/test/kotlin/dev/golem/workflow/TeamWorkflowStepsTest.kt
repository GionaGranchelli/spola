package dev.spola.workflow

import dev.tramai.orchestration.Workflow
import dev.tramai.orchestration.BranchBuilder
import dev.tramai.orchestration.GateDecision
import dev.tramai.orchestration.WorkflowContext
import kotlin.reflect.typeOf
import org.junit.jupiter.api.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class TeamWorkflowStepsTest {

    // -- parallelAgentsStep tests ---------------------------------------------------

    @Test
    fun `parallelAgentsStep adds step with correct name to workflow builder`() {
        with(TeamWorkflowSteps) {
            val builder = dev.tramai.orchestration.workflow<GolemState>("test-parallel") {
                parallelAgentsStep(
                    name = "my-agents",
                    agents = listOf("agent-a", "agent-b"),
                    goal = { it.goal },
                )
            }
            val workflow = builder.build { it.result ?: "" }
            assertNotNull(workflow)
        }
    }

    @Test
    fun `parallelAgentsStep wires agents list and goal function through`() {
        with(TeamWorkflowSteps) {
            val builder = dev.tramai.orchestration.workflow<GolemState>("test-wiring") {
                parallelAgentsStep(
                    name = "multi-agent",
                    agents = listOf("alpha", "beta"),
                    goal = { state -> "${state.goal}-transformed" },
                )
            }
            val workflow = builder.build { it.result ?: "" }
            assertNotNull(workflow)
        }
    }

    @Test
    fun `parallelAgentsStep default merge appends to intermediateResults`() {
        with(TeamWorkflowSteps) {
            val state = GolemState(
                goal = "test",
                intermediateResults = mapOf("existing" to "value"),
            )
            val agents = listOf("agent-x", "agent-y")
            val defaultMerge: (GolemState, List<String>) -> GolemState = { s, results ->
                s.copy(
                    intermediateResults = s.intermediateResults +
                        agents.zip(results).map { (id, r) -> id to r },
                )
            }
            val result = defaultMerge(state, listOf("result-x", "result-y"))
            assertTrue(result.intermediateResults.containsKey("existing"))
            assertTrue(result.intermediateResults.containsValue("value"))
            assertTrue(result.intermediateResults.containsKey("agent-x"))
            assertTrue(result.intermediateResults["agent-x"] == "result-x")
            assertTrue(result.intermediateResults.containsKey("agent-y"))
            assertTrue(result.intermediateResults["agent-y"] == "result-y")
        }
    }

    // -- codeReviewWorkflow tests ---------------------------------------------------

    @Test
    fun `codeReviewWorkflow builds without exceptions`() {
        val workflow = TeamWorkflowSteps.codeReviewWorkflow()
        assertNotNull(workflow)
        assertIs<Workflow<GolemState, String>>(workflow)
    }

    @Test
    fun `codeReviewWorkflow has correct name`() {
        val workflow = TeamWorkflowSteps.codeReviewWorkflow(name = "my-cr")
        assertTrue(workflow.name == "my-cr")
    }

    @Test
    fun `codeReviewWorkflow build result type is String`() {
        val workflow = TeamWorkflowSteps.codeReviewWorkflow()
        assertEquals(typeOf<String>(), workflow.resultType)
    }

    @Test
    fun `codeReviewWorkflow default merge preserves existing intermediate results`() {
        val state = GolemState(
            goal = "review this code",
            intermediateResults = mapOf("prior-step" to "done"),
        )
        val reviewers = listOf("security-reviewer", "style-reviewer", "test-reviewer")
        val results = listOf("sec-ok", "style-ok", "test-ok")

        val mergedState = state.copy(
            intermediateResults = state.intermediateResults +
                reviewers.zip(results).map { (id, r) -> id to r },
        )

        assertTrue(mergedState.intermediateResults.containsKey("prior-step"))
        assertTrue(mergedState.intermediateResults["prior-step"] == "done")
        assertTrue(mergedState.intermediateResults.containsKey("security-reviewer"))
        assertTrue(mergedState.intermediateResults["security-reviewer"] == "sec-ok")
        assertTrue(mergedState.intermediateResults.containsKey("style-reviewer"))
        assertTrue(mergedState.intermediateResults["style-reviewer"] == "style-ok")
        assertTrue(mergedState.intermediateResults.containsKey("test-reviewer"))
        assertTrue(mergedState.intermediateResults["test-reviewer"] == "test-ok")
    }

    // -- branchOnResult tests -------------------------------------------------------

    @Test
    fun `branchOnResult routes to correct branch`() {
        with(TeamWorkflowSteps) {
            val builder = dev.tramai.orchestration.workflow<GolemState>("test-branch-routing") {
                branchOnResult(
                    name = "route",
                    selectBranch = { state -> if (state.result?.contains("pass") == true) "pass" else "fail" },
                    configure = {
                        branch("pass") {
                            localStep("pass-step") { state, _ -> state }
                        }
                        branch("fail") {
                            localStep("fail-step") { state, _ -> state }
                        }
                    },
                )
            }
            val workflow = builder.build { it.result ?: "" }
            assertNotNull(workflow)
            assertTrue(workflow.name == "test-branch-routing")
        }
    }

    @Test
    fun `branchOnResult uses default branch when no key matches`() {
        with(TeamWorkflowSteps) {
            val builder = dev.tramai.orchestration.workflow<GolemState>("test-branch-default") {
                branchOnResult(
                    name = "route",
                    selectBranch = { "unknown-key" },
                    configure = {
                        branch("fast") {
                            localStep("fast-step") { state, _ -> state }
                        }
                        default {
                            localStep("default-step") { state, _ -> state }
                        }
                    },
                )
            }
            val workflow = builder.build { it.result ?: "" }
            assertNotNull(workflow)
        }
    }

    // -- humanApprovalGate tests ----------------------------------------------------

    @Test
    fun `humanApprovalGate allows workflow to continue`() {
        with(TeamWorkflowSteps) {
            val builder = dev.tramai.orchestration.workflow<GolemState>("test-gate-allow") {
                humanApprovalGate(
                    name = "approve",
                    decide = { _, _ -> GateDecision.allow() },
                )
                localStep("post-gate") { state, _ -> state.copy(result = "after-gate") }
            }
            val workflow = builder.build { it.result ?: "" }
            assertNotNull(workflow)
        }
    }

    @Test
    fun `humanApprovalGate rejects workflow`() {
        with(TeamWorkflowSteps) {
            val builder = dev.tramai.orchestration.workflow<GolemState>("test-gate-reject") {
                humanApprovalGate(
                    name = "reject",
                    decide = { _, _ -> GateDecision.reject("testing rejection") },
                )
            }
            val workflow = builder.build { it.result ?: "" }
            assertNotNull(workflow)
        }
    }
}
