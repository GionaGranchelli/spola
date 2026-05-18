package dev.spola.workflow

import dev.spola.AgentRunObserver
import dev.spola.GolemConfig
import dev.spola.GolemFactory
import dev.spola.agent.AgentDefinition
import dev.tramai.orchestration.WorkflowBuilder

/**
 * Extension functions on WorkflowBuilder<GolemState> for Golem-specific steps.
 */
object WorkflowSteps {

    /**
     * Step that runs a Golem agent as an AI operation.
     * Wraps an existing GolemAgent.run() call inside the workflow.
     */
    suspend fun runGolemAgent(
        agentDef: AgentDefinition?,
        config: GolemConfig,
        persona: String,
        goal: String,
        observer: AgentRunObserver? = null,
    ): String {
        val instance = if (agentDef != null) {
            GolemFactory.createFromAgentDefinition(
                agentDef = agentDef,
                config = config,
                observer = observer,
            )
        } else {
            GolemFactory.create(
                config = config,
                observer = observer,
            )
        }

        return try {
            instance.agent.run(persona, goal, instance.observer)
        } finally {
            instance.close()
        }
    }
}

private data class GolemAgentStepInput(
    val agentDef: AgentDefinition?,
    val config: GolemConfig,
    val persona: String,
    val goal: String,
    val observer: AgentRunObserver?,
)

/**
 * Make a Golem agent run available as a workflow step using `aiStep`.
 * This is the primary integration point: wraps GolemAgent.run() as a workflow-compatible AI operation.
 */
fun WorkflowBuilder<GolemState>.golemAgentStep(
    name: String = "run-agent",
    persona: (GolemState) -> String,
    goal: (GolemState) -> String = { it.goal },
    observer: (GolemState) -> AgentRunObserver? = { null },
    merge: (GolemState, String) -> GolemState = { state, result ->
        state.copy(
            result = result,
            intermediateResults = state.intermediateResults + (name to result),
        )
    },
): WorkflowBuilder<GolemState> = apply {
    aiStep(
        name = name,
        input = { state ->
            GolemAgentStepInput(
                agentDef = state.agentDef,
                config = state.config,
                persona = persona(state),
                goal = goal(state),
                observer = observer(state),
            )
        },
        invoke = { input ->
            WorkflowSteps.runGolemAgent(
                agentDef = input.agentDef,
                config = input.config,
                persona = input.persona,
                goal = input.goal,
                observer = input.observer,
            )
        },
        merge = merge,
    )
}
