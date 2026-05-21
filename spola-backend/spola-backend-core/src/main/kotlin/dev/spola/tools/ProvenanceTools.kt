package dev.spola.tools

import dev.spola.Tool
import dev.spola.ToolParameter
import dev.spola.ToolParameterType
import dev.spola.ToolRegistry
import dev.spola.ToolResult
import dev.spola.checkpoint.CheckpointManager
import dev.spola.jvm.ProvenanceBundle
import dev.spola.metrics.SpolaMetrics

fun registerProvenanceTools(
    registry: ToolRegistry,
    manager: CheckpointManager,
    metrics: SpolaMetrics? = null,
    model: String = "unknown",
) {
    registry.register(Tool(
        name = "provenance_export",
        description = "Export a provenance bundle for a session in JSON or HTML format.",
        parameters = listOf(
            ToolParameter("sessionId", "Checkpoint session id", ToolParameterType.STRING),
            ToolParameter("format", "Export format: json or html", ToolParameterType.STRING),
        ),
        execute = { args ->
            val sessionId = (args["sessionId"] as? String)?.trim()
                ?: return@Tool ToolResult.fail("Missing required argument: sessionId")
            val format = (args["format"] as? String)?.trim()?.lowercase()
                ?: return@Tool ToolResult.fail("Missing required argument: format")
            val bundle = ProvenanceBundle.fromCheckpoint(manager, sessionId, metrics, model)
            when (format) {
                "json" -> ToolResult.ok(bundle.toJson())
                "html" -> ToolResult.ok(bundle.toHtml())
                else -> ToolResult.fail("Unsupported format: $format")
            }
        },
    ))

    registry.register(Tool(
        name = "provenance_list",
        description = "List sessions that can be exported as provenance bundles.",
        parameters = emptyList(),
        execute = {
            val sessions = manager.list().map { it.sessionId }.distinct()
            ToolResult.ok(if (sessions.isEmpty()) "No provenance bundles available." else sessions.joinToString("\n"))
        },
    ))

    registry.register(Tool(
        name = "provenance_info",
        description = "Show a brief summary for a provenance bundle session id.",
        parameters = listOf(
            ToolParameter("bundleId", "Bundle id, currently equal to session id", ToolParameterType.STRING),
        ),
        execute = { args ->
            val bundleId = (args["bundleId"] as? String)?.trim()
                ?: return@Tool ToolResult.fail("Missing required argument: bundleId")
            val bundle = ProvenanceBundle.fromCheckpoint(manager, bundleId, metrics, model)
            ToolResult.ok(
                """
                sessionId=${bundle.sessionId}
                version=${bundle.version}
                model=${bundle.model}
                toolCalls=${bundle.toolCalls.size}
                testResults=${bundle.testResults.size}
                timestamps=${bundle.timestamps.size}
                """.trimIndent(),
            )
        },
    ))
}
