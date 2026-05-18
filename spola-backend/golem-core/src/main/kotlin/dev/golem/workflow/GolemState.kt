package dev.spola.workflow

import dev.spola.ChatMessage
import dev.spola.GolemConfig
import dev.spola.agent.AgentDefinition

/**
 * State flowing through a Golem Workflow.
 * Carried across steps, checkpointed for resume.
 */
data class GolemState(
    val goal: String,
    val config: GolemConfig = GolemConfig(),
    val agentDef: AgentDefinition? = null,
    val conversation: List<ChatMessage> = emptyList(),
    val turnCount: Int = 0,
    val intermediateResults: Map<String, String> = emptyMap(),
    val result: String? = null,
    val workflowNestingDepth: Int = 0,
) {
    companion object {
        fun initial(
            goal: String,
            config: GolemConfig = GolemConfig(),
            workflowNestingDepth: Int = 0,
        ): GolemState = GolemState(goal = goal, config = config, workflowNestingDepth = workflowNestingDepth)
    }
}
