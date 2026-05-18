package dev.spola.workflow

import dev.spola.GolemConfig
import dev.spola.GolemFactory
import dev.spola.agent.SqliteAgentStore
import dev.tramai.orchestration.BranchBuilder
import dev.tramai.orchestration.GateDecision
import dev.tramai.orchestration.Workflow
import dev.tramai.orchestration.WorkflowBuilder
import dev.tramai.orchestration.WorkflowContext
import dev.tramai.orchestration.workflow
import java.time.Duration

/**
 * Extension functions on [WorkflowBuilder] for multi-agent team orchestration.
 *
 * These steps build on TramAI's built-in [parallelStep] and [aiStep] to provide
 * Golem-specific conveniences such as concurrent agent delegation and pre-built
 * code review team workflows.
 */
object TeamWorkflowSteps {

    fun WorkflowBuilder<GolemState>.jvmDebugStep(
        name: String = "jvm-debug",
        goal: (GolemState) -> String = { state ->
            """
            Diagnose and fix JVM build or test failures for this project.
            Use jvm_project_overview, jvm_symbol_search, jvm_failure_explain, jvm_change_impact, and jvm_verify_plan.
            Goal: ${state.goal}
            """.trimIndent()
        },
    ): WorkflowBuilder<GolemState> = apply {
        golemAgentStep(
            name = name,
            persona = { "You are a JVM debugging specialist focused on Kotlin/Gradle repositories." },
            goal = goal,
        )
    }

    fun WorkflowBuilder<GolemState>.jvmRefactorStep(
        name: String = "jvm-refactor",
        goal: (GolemState) -> String = { state ->
            """
            Analyze a JVM refactor request, inspect project structure, assess impact, and propose safe edits.
            Use jvm_project_overview, jvm_dependency_trace, jvm_symbol_search, jvm_change_impact, and jvm_verify_plan.
            Goal: ${state.goal}
            """.trimIndent()
        },
    ): WorkflowBuilder<GolemState> = apply {
        golemAgentStep(
            name = name,
            persona = { "You are a JVM refactoring specialist for multi-module Gradle codebases." },
            goal = goal,
        )
    }

    fun WorkflowBuilder<GolemState>.jvmMigrationStep(
        name: String = "jvm-migration",
        goal: (GolemState) -> String = { state ->
            """
            Plan and execute a JVM dependency or framework migration across Gradle modules.
            Use jvm_project_overview, jvm_dependency_trace, jvm_context_pack, and jvm_verify_plan.
            Goal: ${state.goal}
            """.trimIndent()
        },
    ): WorkflowBuilder<GolemState> = apply {
        golemAgentStep(
            name = name,
            persona = { "You are a JVM migration specialist focused on safe, incremental upgrades." },
            goal = goal,
        )
    }

    /**
     * Runs multiple Golem agents concurrently by making direct in-process calls
     * to [GolemFactory.createFromAgentDefinition] for each agent.
     *
     * Each agent receives its goal and runs in the current process using its
     * stored definition, eliminating the need for an external HTTP API server.
     *
     * @param name the workflow step name.
     * @param agents list of agent IDs to invoke concurrently.
     * @param goal function that derives the goal string from the current state.
     * @param config the [GolemConfig] to use for agent creation and store access.
     * @param merge function that merges the list of agent results back into [GolemState].
     *   Default behaviour appends each agent-id / result pair to [GolemState.intermediateResults].
     * @return this [WorkflowBuilder] for chaining.
     */
    @JvmOverloads
    fun WorkflowBuilder<GolemState>.parallelAgentsStep(
        name: String,
        agents: List<String>,
        goal: (GolemState) -> String,
        config: GolemConfig = GolemConfig(),
        merge: (GolemState, List<String>) -> GolemState = { state, results ->
            state.copy(
                intermediateResults = state.intermediateResults +
                    agents.zip(results).map { (id, result) -> id to result },
            )
        },
    ): WorkflowBuilder<GolemState> = apply {
        parallelStep(
            name = name,
            items = { state -> agents.map { it to goal(state) } },
            invoke = { (agentId, agentGoal) ->
                val agentDef = SqliteAgentStore(config.agentsDbPath).get(agentId)
                    ?: throw RuntimeException("Agent '$agentId' not found")
                val instance = GolemFactory.createFromAgentDefinition(agentDef = agentDef, config = config)
                try {
                    instance.agent.run(agentDef.systemPrompt, agentGoal, instance.observer)
                } finally {
                    instance.close()
                }
            },
            merge = merge,
        )
    }

    /**
     * Creates a pre-built code review workflow that runs three specialized
     * reviewers (security, style, test) in parallel and then produces a
     * final summary via an AI step.
     *
     * The workflow consists of:
     * 1. A [parallelAgentsStep] that dispatches to `security-reviewer`,
     *    `style-reviewer`, and `test-reviewer` agents concurrently.
     * 2. An [aiStep] that aggregates all three reviews and produces a
     *    consolidated summary stored in [GolemState.result].
     *
     * @param name the workflow name (default: "code-review-team").
     * @param definitionVersion the workflow definition version (default: "1").
     * @return a [Workflow] that accepts a [GolemState] and produces a [String] result.
     */
    fun codeReviewWorkflow(
        name: String = "code-review-team",
        definitionVersion: String = "1",
        summarizer: (String) -> String = { it },
    ): Workflow<GolemState, String> {
        val reviewers = listOf("security-reviewer", "style-reviewer", "test-reviewer")

        return workflow<GolemState>(name, definitionVersion) {
            parallelAgentsStep(
                name = "parallel-review",
                agents = reviewers,
                goal = { it.goal },
                merge = { state, results ->
                    state.copy(
                        intermediateResults = state.intermediateResults +
                            reviewers.zip(results).map { (id, result) -> id to result },
                    )
                },
            )
            aiStep(
                name = "summarize",
                input = { state ->
                    val securityReview = state.intermediateResults["security-reviewer"] ?: ""
                    val styleReview = state.intermediateResults["style-reviewer"] ?: ""
                    val testReview = state.intermediateResults["test-reviewer"] ?: ""
                    """
                        |Aggregate the following code reviews into a concise final summary.
                        |
                        |## Security Review
                        |$securityReview
                        |
                        |## Style Review
                        |$styleReview
                        |
                        |## Test Review
                        |$testReview
                    """.trimMargin()
                },
                invoke = { input -> summarizer(input) },
                merge = { state, summary -> state.copy(result = summary) },
            )
        }.build { it.result ?: "no result" }
    }

    /**
     * Routes workflow execution based on a state predicate.
     * Wraps TramAI's [branchStep] for Golem workflows.
     *
     * @param name the branch step name.
     * @param selectBranch function that derives the branch key from the current state.
     * @param configure configures the named branches and optional default branch.
     * @return this [WorkflowBuilder] for chaining.
     */
    fun WorkflowBuilder<GolemState>.branchOnResult(
        name: String,
        selectBranch: (GolemState) -> String,
        configure: BranchBuilder<GolemState>.() -> Unit,
    ): WorkflowBuilder<GolemState> = apply {
        branchStep(name = name, select = selectBranch, configure = configure)
    }

    /**
     * Inserts an approval gate that can stop or allow workflow execution.
     * Wraps TramAI's [gateStep] for Golem workflows.
     *
     * @param name the gate step name (default: "approval-gate").
     * @param decide function that returns [GateDecision.allowed] = true to continue
     *   or [GateDecision.allowed] = false with a [GateDecision.reason] to stop.
     * @return this [WorkflowBuilder] for chaining.
     */
    fun WorkflowBuilder<GolemState>.humanApprovalGate(
        name: String = "approval-gate",
        decide: suspend (GolemState, WorkflowContext) -> GateDecision,
    ): WorkflowBuilder<GolemState> = apply {
        gateStep(name = name) { state, context ->
            decide(state, context)
        }
    }
}
