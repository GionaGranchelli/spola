package dev.spola.workflow

import dev.spola.SpolaConfig
import dev.spola.SpolaFactory
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
 * Spola-specific conveniences such as concurrent agent delegation and pre-built
 * code review team workflows.
 */
object TeamWorkflowSteps {

    fun WorkflowBuilder<SpolaState>.jvmDebugStep(
        name: String = "jvm-debug",
        goal: (SpolaState) -> String = { state ->
            """
            Diagnose and fix JVM build or test failures for this project.
            Use jvm_project_overview, jvm_symbol_search, jvm_failure_explain, jvm_change_impact, and jvm_verify_plan.
            Goal: ${state.goal}
            """.trimIndent()
        },
    ): WorkflowBuilder<SpolaState> = apply {
        spolaAgentStep(
            name = name,
            persona = { "You are a JVM debugging specialist focused on Kotlin/Gradle repositories." },
            goal = goal,
        )
    }

    fun WorkflowBuilder<SpolaState>.jvmRefactorStep(
        name: String = "jvm-refactor",
        goal: (SpolaState) -> String = { state ->
            """
            Analyze a JVM refactor request, inspect project structure, assess impact, and propose safe edits.
            Use jvm_project_overview, jvm_dependency_trace, jvm_symbol_search, jvm_change_impact, and jvm_verify_plan.
            Goal: ${state.goal}
            """.trimIndent()
        },
    ): WorkflowBuilder<SpolaState> = apply {
        spolaAgentStep(
            name = name,
            persona = { "You are a JVM refactoring specialist for multi-module Gradle codebases." },
            goal = goal,
        )
    }

    fun WorkflowBuilder<SpolaState>.jvmMigrationStep(
        name: String = "jvm-migration",
        goal: (SpolaState) -> String = { state ->
            """
            Plan and execute a JVM dependency or framework migration across Gradle modules.
            Use jvm_project_overview, jvm_dependency_trace, jvm_context_pack, and jvm_verify_plan.
            Goal: ${state.goal}
            """.trimIndent()
        },
    ): WorkflowBuilder<SpolaState> = apply {
        spolaAgentStep(
            name = name,
            persona = { "You are a JVM migration specialist focused on safe, incremental upgrades." },
            goal = goal,
        )
    }

    /**
     * Runs multiple Spola agents concurrently by making direct in-process calls
     * to [SpolaFactory.createFromAgentDefinition] for each agent.
     *
     * Each agent receives its goal and runs in the current process using its
     * stored definition, eliminating the need for an external HTTP API server.
     *
     * @param name the workflow step name.
     * @param agents list of agent IDs to invoke concurrently.
     * @param goal function that derives the goal string from the current state.
     * @param config the [SpolaConfig] to use for agent creation and store access.
     * @param merge function that merges the list of agent results back into [SpolaState].
     *   Default behaviour appends each agent-id / result pair to [SpolaState.intermediateResults].
     * @return this [WorkflowBuilder] for chaining.
     */
    @JvmOverloads
    fun WorkflowBuilder<SpolaState>.parallelAgentsStep(
        name: String,
        agents: List<String>,
        goal: (SpolaState) -> String,
        config: SpolaConfig = SpolaConfig(),
        merge: (SpolaState, List<String>) -> SpolaState = { state, results ->
            state.copy(
                intermediateResults = state.intermediateResults +
                    agents.zip(results).map { (id, result) -> id to result },
            )
        },
    ): WorkflowBuilder<SpolaState> = apply {
        parallelStep(
            name = name,
            items = { state -> agents.map { it to goal(state) } },
            invoke = { (agentId, agentGoal) ->
                val agentDef = SqliteAgentStore(config.agentsDbPath).get(agentId)
                    ?: throw RuntimeException("Agent '$agentId' not found")
                val instance = SpolaFactory.createFromAgentDefinition(agentDef = agentDef, config = config)
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
     *    consolidated summary stored in [SpolaState.result].
     *
     * @param name the workflow name (default: "code-review-team").
     * @param definitionVersion the workflow definition version (default: "1").
     * @return a [Workflow] that accepts a [SpolaState] and produces a [String] result.
     */
    fun codeReviewWorkflow(
        name: String = "code-review-team",
        definitionVersion: String = "1",
        summarizer: (String) -> String = { it },
    ): Workflow<SpolaState, String> {
        val reviewers = listOf("security-reviewer", "style-reviewer", "test-reviewer")

        return workflow<SpolaState>(name, definitionVersion) {
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
     * Wraps TramAI's [branchStep] for Spola workflows.
     *
     * @param name the branch step name.
     * @param selectBranch function that derives the branch key from the current state.
     * @param configure configures the named branches and optional default branch.
     * @return this [WorkflowBuilder] for chaining.
     */
    fun WorkflowBuilder<SpolaState>.branchOnResult(
        name: String,
        selectBranch: (SpolaState) -> String,
        configure: BranchBuilder<SpolaState>.() -> Unit,
    ): WorkflowBuilder<SpolaState> = apply {
        branchStep(name = name, select = selectBranch, configure = configure)
    }

    /**
     * Inserts an approval gate that can stop or allow workflow execution.
     * Wraps TramAI's [gateStep] for Spola workflows.
     *
     * @param name the gate step name (default: "approval-gate").
     * @param decide function that returns [GateDecision.allowed] = true to continue
     *   or [GateDecision.allowed] = false with a [GateDecision.reason] to stop.
     * @return this [WorkflowBuilder] for chaining.
     */
    fun WorkflowBuilder<SpolaState>.humanApprovalGate(
        name: String = "approval-gate",
        decide: suspend (SpolaState, WorkflowContext) -> GateDecision,
    ): WorkflowBuilder<SpolaState> = apply {
        gateStep(name = name) { state, context ->
            decide(state, context)
        }
    }
}
