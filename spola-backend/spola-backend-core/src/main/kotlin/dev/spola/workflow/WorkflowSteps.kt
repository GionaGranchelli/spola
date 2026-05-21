package dev.spola.workflow

import dev.spola.AgentRunObserver
import dev.spola.SpolaConfig
import dev.spola.SpolaFactory
import dev.spola.agent.AgentDefinition
import dev.tramai.orchestration.WorkflowBuilder

/**
 * Extension functions on WorkflowBuilder<SpolaState> for Spola-specific steps.
 */
object WorkflowSteps {

    /**
     * Step that runs a Spola agent as an AI operation.
     * Wraps an existing SpolaAgent.run() call inside the workflow.
     */
    suspend fun runSpolaAgent(
        agentDef: AgentDefinition?,
        config: SpolaConfig,
        persona: String,
        goal: String,
        observer: AgentRunObserver? = null,
    ): String {
        val instance = if (agentDef != null) {
            SpolaFactory.createFromAgentDefinition(
                agentDef = agentDef,
                config = config,
                observer = observer,
            )
        } else {
            SpolaFactory.create(
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

private data class SpolaAgentStepInput(
    val agentDef: AgentDefinition?,
    val config: SpolaConfig,
    val persona: String,
    val goal: String,
    val observer: AgentRunObserver?,
)

/**
 * Make a Spola agent run available as a workflow step using `aiStep`.
 * This is the primary integration point: wraps SpolaAgent.run() as a workflow-compatible AI operation.
 */
fun WorkflowBuilder<SpolaState>.spolaAgentStep(
    name: String = "run-agent",
    persona: (SpolaState) -> String,
    goal: (SpolaState) -> String = { it.goal },
    observer: (SpolaState) -> AgentRunObserver? = { null },
    merge: (SpolaState, String) -> SpolaState = { state, result ->
        state.copy(
            result = result,
            intermediateResults = state.intermediateResults + (name to result),
        )
    },
): WorkflowBuilder<SpolaState> = apply {
    aiStep(
        name = name,
        input = { state ->
            SpolaAgentStepInput(
                agentDef = state.agentDef,
                config = state.config,
                persona = persona(state),
                goal = goal(state),
                observer = observer(state),
            )
        },
        invoke = { input ->
            WorkflowSteps.runSpolaAgent(
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
