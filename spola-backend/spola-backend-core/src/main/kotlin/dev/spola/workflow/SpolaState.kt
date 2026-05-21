package dev.spola.workflow

import dev.spola.ChatMessage
import dev.spola.SpolaConfig
import dev.spola.agent.AgentDefinition

/**
 * State flowing through a Spola Workflow.
 * Carried across steps, checkpointed for resume.
 */
data class SpolaState(
    val goal: String,
    val config: SpolaConfig = SpolaConfig(),
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
            config: SpolaConfig = SpolaConfig(),
            workflowNestingDepth: Int = 0,
        ): SpolaState = SpolaState(goal = goal, config = config, workflowNestingDepth = workflowNestingDepth)
    }
}
