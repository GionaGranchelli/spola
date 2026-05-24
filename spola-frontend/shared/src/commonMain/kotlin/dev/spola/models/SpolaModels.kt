package dev.spola.models

import kotlinx.serialization.Serializable

@Serializable
data class AgentRunRequest(
    val goal: String,
    val model: String? = null,
    val persona: String? = null
)

@Serializable
data class AgentRunResponse(
    val result: String,
    val turns: Int
)

@Serializable
data class ExecRequest(
    val command: String,
)

@Serializable
data class HealthResponse(
    val status: String,
    val version: String
)

@Serializable
data class ToolInfo(
    val name: String,
    val description: String,
    val parameters: Map<String, String>
)

@Serializable
data class ScheduledJobResponse(
    val id: String,
    val name: String,
    val goal: String,
    val cronExpression: String,
    val enabled: Boolean,
    val createdAt: Long,
    val nextRunAt: Long
)
