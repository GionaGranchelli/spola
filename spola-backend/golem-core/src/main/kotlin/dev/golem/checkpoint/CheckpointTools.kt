package dev.spola.checkpoint

import dev.spola.Tool
import dev.spola.ToolParameter
import dev.spola.ToolParameterType
import dev.spola.ToolRegistry
import dev.spola.ToolResult

/**
 * Register checkpoint tools (checkpoint_save, checkpoint_list, checkpoint_resume)
 * into the tool registry.
 */
fun registerCheckpointTools(
    registry: ToolRegistry,
    manager: CheckpointManager,
) {
    registry.register(Tool(
        name = "checkpoint_save",
        description = "Save the current agent conversation state as a checkpoint. Returns the checkpoint ID and session ID for later resumption.",
        parameters = listOf(
            ToolParameter("sessionId", "Session identifier for the checkpoint.", ToolParameterType.STRING),
            ToolParameter("turnNumber", "Current turn number being checkpointed.", ToolParameterType.INTEGER),
            ToolParameter("conversationJson", "JSON-serialized conversation to save.", ToolParameterType.STRING),
        ),
        execute = { args ->
            try {
                val sessionId = (args["sessionId"] as? String)
                    ?: return@Tool ToolResult.fail("Missing required argument: sessionId")
                val turnNumber = (args["turnNumber"] as? Int)
                    ?: return@Tool ToolResult.fail("Missing required argument: turnNumber")
                val conversationJson = (args["conversationJson"] as? String)
                    ?: return@Tool ToolResult.fail("Missing required argument: conversationJson")

                val id = manager.saveRaw(sessionId, turnNumber, conversationJson)
                ToolResult.ok(
                    """
                    Checkpoint saved:
                    id: $id
                    sessionId: $sessionId
                    turn: $turnNumber
                    """.trimIndent(),
                )
            } catch (e: Exception) {
                ToolResult.fail("Failed to save checkpoint: ${e.message}")
            }
        },
    ))

    registry.register(Tool(
        name = "checkpoint_list",
        description = "List all available checkpoints with their session IDs, turn numbers, and creation times.",
        parameters = emptyList(),
        execute = {
            try {
                val checkpoints = manager.list()
                if (checkpoints.isEmpty()) {
                    ToolResult.ok("No checkpoints available.")
                } else {
                    ToolResult.ok(
                        checkpoints.joinToString(separator = "\n\n") { cp ->
                            """
                            id: ${cp.id}
                            sessionId: ${cp.sessionId}
                            turn: ${cp.turnNumber}
                            createdAt: ${cp.createdAt}
                            """.trimIndent()
                        },
                    )
                }
            } catch (e: Exception) {
                ToolResult.fail("Failed to list checkpoints: ${e.message}")
            }
        },
    ))

    registry.register(Tool(
        name = "checkpoint_resume",
        description = "Load and return the conversation state from the most recent checkpoint for a given session ID. Returns the serialized conversation JSON that can be used to resume the agent.",
        parameters = listOf(
            ToolParameter("sessionId", "Session identifier to resume from.", ToolParameterType.STRING),
        ),
        execute = { args ->
            try {
                val sessionId = (args["sessionId"] as? String)
                    ?: return@Tool ToolResult.fail("Missing required argument: sessionId")

                val state = manager.load(sessionId)
                if (state == null) {
                    ToolResult.fail("No checkpoint found for session: $sessionId")
                } else {
                    ToolResult.ok(
                        """
                        Checkpoint found:
                        sessionId: ${state.sessionId}
                        turnNumber: ${state.turnNumber}
                        createdAt: ${state.createdAt}
                        conversationJson: ${state.conversationJson}
                        """.trimIndent(),
                    )
                }
            } catch (e: Exception) {
                ToolResult.fail("Failed to resume checkpoint: ${e.message}")
            }
        },
    ))
}
