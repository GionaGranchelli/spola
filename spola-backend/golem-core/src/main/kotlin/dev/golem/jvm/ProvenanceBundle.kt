package dev.spola.jvm

import dev.spola.GolemVersion
import dev.spola.checkpoint.CheckpointManager
import dev.spola.metrics.GolemMetrics
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ProvenanceBundle(
    val version: String,
    val sessionId: String,
    val toolCalls: List<ProvenanceToolCall>,
    val codeDiff: String,
    val testResults: List<String>,
    val model: String,
    val timestamps: List<String>,
    val metrics: ProvenanceMetrics,
) {
    fun toJson(): String = json.encodeToString(this)

    fun toHtml(): String = """
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <meta charset="utf-8" />
            <title>Golem Provenance Bundle ${escape(sessionId)}</title>
            <style>
                body { font-family: sans-serif; margin: 2rem; line-height: 1.5; }
                pre { background: #f5f5f5; padding: 1rem; overflow-x: auto; }
                table { border-collapse: collapse; width: 100%; margin-bottom: 2rem; }
                td, th { border: 1px solid #ddd; padding: 0.5rem; text-align: left; }
            </style>
        </head>
        <body>
            <h1>Provenance Bundle</h1>
            <p><strong>Session:</strong> ${escape(sessionId)}</p>
            <p><strong>Version:</strong> ${escape(version)}</p>
            <p><strong>Model:</strong> ${escape(model)}</p>
            <h2>Tool Calls</h2>
            <table>
                <thead><tr><th>Name</th><th>Arguments</th></tr></thead>
                <tbody>
                    ${toolCalls.joinToString("") { "<tr><td>${escape(it.name)}</td><td><pre>${escape(it.argumentsJson)}</pre></td></tr>" }}
                </tbody>
            </table>
            <h2>Test Results</h2>
            <pre>${escape(testResults.joinToString("\n"))}</pre>
            <h2>Diff</h2>
            <pre>${escape(codeDiff)}</pre>
            <h2>Metrics</h2>
            <pre>${escape(metrics.toString())}</pre>
        </body>
        </html>
    """.trimIndent()

    companion object {
        private val json = Json { prettyPrint = true }

        fun fromCheckpoint(
            manager: CheckpointManager,
            sessionId: String,
            metrics: GolemMetrics? = null,
            model: String = "unknown",
        ): ProvenanceBundle {
            val checkpoints = manager.listForSession(sessionId)
            val latestConversation = manager.loadConversation(sessionId).orEmpty()
            val toolCalls = latestConversation
                .filterIsInstance<dev.spola.AssistantMessage>()
                .flatMap { message ->
                    message.toolCalls.map { call ->
                        val argumentsJson = runCatching {
                            com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().writeValueAsString(call.arguments)
                        }.getOrDefault("{}")
                        ProvenanceToolCall(name = call.name, argumentsJson = argumentsJson)
                    }
                }
            val toolResults = latestConversation
                .filterIsInstance<dev.spola.ToolResultMessage>()
                .mapNotNull { result ->
                    val text = result.content.trim()
                    if (text.contains("test", ignoreCase = true) ||
                        text.contains("failed", ignoreCase = true) ||
                        text.contains("passed", ignoreCase = true)
                    ) {
                        text
                    } else {
                        null
                    }
                }
            val diff = checkpoints.firstOrNull()?.diff ?: manager.computeGitDiff().orEmpty()
            val metricsHistory = metrics?.getHistory().orEmpty()
            return ProvenanceBundle(
                version = GolemVersion.VERSION,
                sessionId = sessionId,
                toolCalls = toolCalls,
                codeDiff = diff,
                testResults = toolResults,
                model = model,
                timestamps = checkpoints.map { it.createdAt },
                metrics = ProvenanceMetrics(
                    historyPoints = metricsHistory.size,
                    latestTimestamp = metricsHistory.lastOrNull()?.timestamp,
                    latestToolCalls = metricsHistory.lastOrNull()?.toolCallsTotal,
                    latestAgentTurns = metricsHistory.lastOrNull()?.agentTurnsTotal,
                ),
            )
        }

        private fun escape(input: String): String = buildString(input.length) {
            input.forEach { ch ->
                when (ch) {
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '&' -> append("&amp;")
                    '"' -> append("&quot;")
                    else -> append(ch)
                }
            }
        }
    }
}

@Serializable
data class ProvenanceToolCall(
    val name: String,
    val argumentsJson: String,
)

@Serializable
data class ProvenanceMetrics(
    val historyPoints: Int,
    val latestTimestamp: Long?,
    val latestToolCalls: Double?,
    val latestAgentTurns: Double?,
)
