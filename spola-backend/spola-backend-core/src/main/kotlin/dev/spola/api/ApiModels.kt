package dev.spola.api

import dev.spola.SpolaVersion
import dev.spola.Tool
import dev.spola.ToolParameter
import dev.spola.ToolParameterType
import dev.spola.ToolResult
import dev.spola.agent.AgentDefinition
import dev.spola.agent.ToolPolicy
import dev.spola.checkpoint.CheckpointData
import dev.spola.memory.MemoryEntry
import dev.spola.scheduler.ScheduledJob
import dev.spola.toTypeString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class HealthResponse(
    val status: String,
    val version: String,
)

@Serializable
data class AgentRunRequest(
    val goal: String,
    val model: String? = null,
    val persona: String? = null,
)

@Serializable
data class AgentRunAgentRequest(
    val agentId: String,
    val goal: String,
)

@Serializable
data class AgentRunResponse(
    val result: String,
    val turns: Int,
)

@Serializable
data class AgentStatusResponse(
    val model: String,
    val provider: String,
    val maxTurns: Int,
    val workingDirectory: String,
    val toolCount: Int,
    val running: Boolean,
)

@Serializable
data class CreateJobRequest(
    val name: String,
    val cronExpression: String,
    val goal: String,
    val enabled: Boolean = true,
    val workflowDefinitionId: String? = null,
)

@Serializable
data class JobsResponse(
    val jobs: List<ScheduledJobResponse>,
)

@Serializable
data class ScheduledJobResponse(
    val id: String,
    val name: String,
    val goal: String,
    val cronExpression: String,
    val enabled: Boolean,
    val workflowDefinitionId: String? = null,
    val createdAt: String,
    val lastRunAt: String? = null,
    val nextRunAt: String,
)

@Serializable
data class DeleteJobResponse(
    val removed: Boolean,
    val id: String,
    val message: String,
)

@Serializable
data class ToolsResponse(
    val tools: List<ToolSchemaResponse>,
)

@Serializable
data class ToolSchemaResponse(
    val name: String,
    val description: String,
    val parameters: ToolParametersSchemaResponse,
    val enabled: Boolean,
)

@Serializable
data class ToolParametersSchemaResponse(
    val type: String,
    val properties: Map<String, ToolParameterSchemaResponse>,
    val required: List<String>,
)

@Serializable
data class ToolParameterSchemaResponse(
    val type: String,
    val description: String,
    val default: JsonElement? = null,
    val enumValues: List<String> = emptyList(),
)

@Serializable
data class MemoryEntriesResponse(
    val entries: List<MemoryEntryResponse>,
    val query: String? = null,
)

@Serializable
data class MemoryEntryResponse(
    val key: String,
    val value: String,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class DeleteMemoryResponse(
    val deleted: Boolean,
    val key: String,
    val message: String,
)

@Serializable
data class StatusEventPayload(
    val status: String,
    val message: String? = null,
)

@Serializable
data class TokenEventPayload(
    val text: String,
)

@Serializable
data class ToolCallEventPayload(
    val id: String,
    val name: String,
    val arguments: Map<String, JsonElement>,
)

@Serializable
data class ToolResultEventPayload(
    val id: String,
    val name: String,
    val success: Boolean,
    val output: String,
    val error: String? = null,
)

@Serializable
data class ErrorEventPayload(
    val error: String,
)

@Serializable
data class CompleteEventPayload(
    val result: String,
    val turns: Int,
)

// Delivery API models

@Serializable
data class TelegramSendRequest(
    val chatId: String,
    val text: String,
)

@Serializable
data class EmailSendRequest(
    val to: String,
    val subject: String,
    val body: String,
)

@Serializable
data class DeliveryResponse(
    val success: Boolean,
    val message: String,
)

@Serializable
data class ConfigResponse(
    val version: String = SpolaVersion.VERSION,
    val effectiveConfigPath: String,
    val workdir: String,
    val persona: String? = null,
    val memoryDb: String,
    val schedulerDb: String,
    val kanbanDb: String,
    val checkpointDb: String,
    val jvmIndexDb: String,
    val sessionsDb: String,
    val pluginsDir: String,
    val agentsDir: String,
    val agentsDb: String,
    val model: String,
    val provider: String,
    val maxTurns: Int,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val apiKey: String? = null,
    val pairingToken: String? = null,
    val telegramBotToken: String? = null,
    val insecure: Boolean,
    val unsafe: Boolean? = null,
    val email: ConfigEmailResponse? = null,
    val tts: ConfigTtsResponse? = null,
    val architectMode: ConfigArchitectModeResponse? = null,
    val pluginsEnabled: Boolean,
    val compressionEnabled: Boolean,
    val autoCheckpoint: Boolean,
    val jvmIndexAutoRefresh: Boolean,
    val metricsEnabled: Boolean,
    val otelEnabled: Boolean,
    val otelEndpoint: String? = null,
    val otelServiceName: String,
    val defaultAgentId: String? = null,
) {
    companion object {
        fun fromConfig(config: dev.spola.SpolaConfig, effectiveConfigPath: String): ConfigResponse {
            return ConfigResponse(
                version = SpolaVersion.VERSION,
                effectiveConfigPath = effectiveConfigPath,
                workdir = config.workingDirectory,
                persona = config.agent.personaPath,
                memoryDb = config.database.memoryDbPath,
                schedulerDb = config.database.schedulerDbPath,
                kanbanDb = config.database.kanbanDbPath,
                checkpointDb = config.database.checkpointDbPath,
                jvmIndexDb = config.database.jvmIndexDbPath,
                sessionsDb = config.database.sessionsDbPath,
                pluginsDir = config.pluginsDir,
                agentsDir = config.agentsDir,
                agentsDb = config.database.agentsDbPath,
                model = config.provider.defaultModel,
                provider = config.provider.defaultProvider,
                maxTurns = config.agent.maxTurns,
                temperature = config.temperature,
                maxTokens = config.maxTokens,
                apiKey = maskSecret(config.security.apiKey),
                pairingToken = maskSecret(config.pairingToken),
                telegramBotToken = maskSecret(config.delivery.telegramToken),
                insecure = config.security.insecure,
                unsafe = null,
                email = ConfigEmailResponse(
                    smtpHost = config.delivery.smtpHost.ifBlank { null },
                    smtpPort = config.delivery.smtpPort,
                    username = config.delivery.smtpUser.ifBlank { null },
                    password = maskSecret(config.delivery.smtpPass.ifBlank { null }),
                    from = config.delivery.fromEmail.ifBlank { null },
                ),
                tts = ConfigTtsResponse(
                    provider = config.tts.ttsProvider,
                    elevenlabsApiKey = maskSecret(config.tts.elevenlabsApiKey),
                    elevenlabsVoiceId = config.tts.elevenlabsVoiceId,
                ),
                architectMode = ConfigArchitectModeResponse(
                    enabled = config.architectMode.enabled,
                    architectModel = config.architectMode.architectModel,
                    architectProvider = config.architectMode.architectProvider,
                    editorModel = config.architectMode.editorModel,
                    editorProvider = config.architectMode.editorProvider,
                ),
                pluginsEnabled = config.pluginsEnabled,
                compressionEnabled = config.compressionEnabled,
                autoCheckpoint = config.autoCheckpoint,
                jvmIndexAutoRefresh = config.jvmIndexAutoRefresh,
                metricsEnabled = config.metrics.metricsEnabled,
                otelEnabled = config.metrics.otelEnabled,
                otelEndpoint = config.metrics.otelEndpoint.ifBlank { null },
                otelServiceName = config.otelServiceName,
                defaultAgentId = config.defaultAgentId,
            )
        }

        private fun maskSecret(value: String?): String? = value?.let { "***" }
    }
}

@Serializable
data class ConfigEmailResponse(
    val smtpHost: String? = null,
    val smtpPort: Int? = null,
    val username: String? = null,
    val password: String? = null,
    val from: String? = null,
)

@Serializable
data class ConfigTtsResponse(
    val provider: String? = null,
    val elevenlabsApiKey: String? = null,
    val elevenlabsVoiceId: String? = null,
)

@Serializable
data class ConfigArchitectModeResponse(
    val enabled: Boolean? = null,
    val architectModel: String? = null,
    val architectProvider: String? = null,
    val editorModel: String? = null,
    val editorProvider: String? = null,
)

@Serializable
data class ConfigSaveResponse(
    val success: Boolean,
    val effectiveConfigPath: String,
    val config: ConfigResponse,
)

// Checkpoint API models

@Serializable
data class CheckpointListResponse(
    val checkpoints: List<CheckpointItemResponse>,
)

@Serializable
data class CheckpointItemResponse(
    val id: Long,
    val sessionId: String,
    val turnNumber: Int,
    val createdAt: String,
    val diff: String? = null,
)

@Serializable
data class CheckpointResumeResponse(
    val sessionId: String,
    val messageCount: Int,
    val messages: List<CheckpointMessageResponse>,
)

@Serializable
data class CheckpointMessageResponse(
    val role: String,
    val content: String,
)

@Serializable
data class CheckpointDiffResponse(
    val id: Long,
    val sessionId: String,
    val turnNumber: Int,
    val createdAt: String,
    val diff: String? = null,
)

// Pairing API models

@Serializable
data class PairingInfoResponse(
    val host: String,
    val port: Int,
    val token: String,
    val trustId: String,
    val version: String,
)

// Session & Model API models (for frontend compatibility)

@Serializable
data class SessionInfo(
    val id: String,
    val title: String,
    val createdAt: Long,
    val lastActiveAt: Long = createdAt,
    val modelId: String,
    val providerId: String = "",
)

@Serializable
data class CreateSessionRequest(
    val title: String,
    val modelId: String? = null,
    val providerId: String? = null,
)

@Serializable
data class SessionModelUpdate(
    val modelId: String,
)

@Serializable
data class ModelInfo(
    val id: String,
    val name: String,
    val provider: String,
    val description: String? = null,
)

internal fun ScheduledJob.toResponse(): ScheduledJobResponse = ScheduledJobResponse(
    id = id,
    name = name,
    goal = goal,
    cronExpression = cronExpression,
    enabled = enabled,
    workflowDefinitionId = workflowDefinitionId,
    createdAt = createdAt.toString(),
    lastRunAt = lastRunAt?.toString(),
    nextRunAt = nextRunAt.toString(),
)

internal fun Tool.toSchemaResponse(enabled: Boolean): ToolSchemaResponse = ToolSchemaResponse(
    name = name,
    description = description,
    parameters = ToolParametersSchemaResponse(
        type = "object",
        properties = parameters.associate { it.name to it.toSchemaResponse() },
        required = parameters.filter { it.required }.map { it.name },
    ),
    enabled = enabled,
)

internal fun ToolParameter.toSchemaResponse(): ToolParameterSchemaResponse = ToolParameterSchemaResponse(
    type = type.toTypeString(),
    description = description,
    default = defaultValue?.toJsonElement(),
    enumValues = enumValues,
)

internal fun MemoryEntry.toResponse(): MemoryEntryResponse = MemoryEntryResponse(
    key = key,
    value = value,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

// Agent API models

@Serializable
data class AgentDefinitionResponse(
    val id: String,
    val name: String,
    val description: String,
    val systemPrompt: String,
    val preferredModel: String,
    val preferredProvider: String,
    val fallbackModel: String? = null,
    val toolPolicy: ToolPolicy,
    val toolsAllowed: List<String> = emptyList(),
    val filesystemAccess: String,
    val shellAccess: Boolean,
    val networkAccess: Boolean,
    val executeCommands: String,
    val memoryScope: String,
    val tags: List<String>,
    val enabled: Boolean,
    val version: Int,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class AgentCreateRequest(
    val id: String,
    val name: String,
    val description: String = "",
    val systemPrompt: String,
    val preferredModel: String,
    val preferredProvider: String,
    val fallbackModel: String? = null,
    val fallbackProvider: String? = null,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val toolPolicy: ToolPolicy = ToolPolicy.ALL,
    val toolsAllowed: List<String> = emptyList(),
    val filesystemAccess: String = "read-write",
    val shellAccess: Boolean = true,
    val networkAccess: Boolean = true,
    val executeCommands: String = "auto",
    val memoryScope: String = "global",
    val tags: List<String> = emptyList(),
    val responseFormat: String = "markdown",
)

@Serializable
data class AgentUpdateRequest(
    val name: String? = null,
    val description: String? = null,
    val systemPrompt: String? = null,
    val preferredModel: String? = null,
    val preferredProvider: String? = null,
    val fallbackModel: String? = null,
    val toolPolicy: ToolPolicy? = null,
    val toolsAllowed: List<String>? = null,
    val filesystemAccess: String? = null,
    val shellAccess: Boolean? = null,
    val networkAccess: Boolean? = null,
    val executeCommands: String? = null,
    val memoryScope: String? = null,
    val tags: List<String>? = null,
    val enabled: Boolean? = null,
    val responseFormat: String? = null,
    val maxTurnsOverride: Int? = null,
)

@Serializable
data class RenderedMemoryResponse(
    val key: String,
    val html: String,
    val updatedAt: String,
)

@Serializable
data class WorkflowRunRequest(
    val workflowName: String,
    val goal: String,
    val definitionId: String? = null,
    val sessionId: String? = null,
    val inputJson: String = "{}",
)

// ── Conversion functions ───────────────────────────────

internal fun AgentDefinition.toResponse(): AgentDefinitionResponse = AgentDefinitionResponse(
    id = id,
    name = name,
    description = description,
    systemPrompt = systemPrompt,
    preferredModel = preferredModel,
    preferredProvider = preferredProvider,
    fallbackModel = fallbackModel,
    toolPolicy = toolPolicy,
    toolsAllowed = toolsAllowed,
    filesystemAccess = filesystemAccess,
    shellAccess = shellAccess,
    networkAccess = networkAccess,
    executeCommands = executeCommands,
    memoryScope = memoryScope,
    tags = tags,
    enabled = enabled,
    version = version,
    createdAt = createdAt,
    updatedAt = updatedAt,
)


internal fun CheckpointData.toResponse(): CheckpointItemResponse = CheckpointItemResponse(
    id = id,
    sessionId = sessionId,
    turnNumber = turnNumber,
    createdAt = createdAt,
)

internal fun toolResultEventPayload(
    toolCall: dev.spola.ToolCall,
    result: ToolResult,
): ToolResultEventPayload = ToolResultEventPayload(
    id = toolCall.id,
    name = toolCall.name,
    success = result.success,
    output = result.output,
    error = result.error,
)

// ── Tool detail API models ───────────────────────────────

@Serializable
data class ParameterInfo(
    val name: String,
    val description: String,
    val type: String,
    val required: Boolean = true,
    val default: JsonElement? = null,
    val enumValues: List<String> = emptyList(),
)

@Serializable
data class ToolDetailResponse(
    val name: String,
    val description: String,
    val parameters: List<ParameterInfo>,
    val enabled: Boolean,
)

@Serializable
data class ToolToggleRequest(
    val enabled: Boolean,
)

// ── Metrics history API models ─────────────────────────

@Serializable
data class MetricsPointResponse(
    val timestamp: Long,
    val agentRunsTotal: Double = 0.0,
    val agentTurnsTotal: Double = 0.0,
    val toolCallsTotal: Double = 0.0,
    val llmCallsTotal: Double = 0.0,
    val llmTokensTotal: Double = 0.0,
)

@Serializable
data class MetricsHistoryResponse(
    val metrics: List<MetricsPointResponse>,
)

internal fun Any.toJsonElement(preserveNulls: Boolean = false): JsonElement =
    dev.spola.util.jsonValueToElement(this, preserveNulls = preserveNulls)

// ── Provider API models ───────────────────────────────

@Serializable
data class ProviderInfoResponse(
    val name: String,
    val type: String,
    val baseUrl: String? = null,
    val model: String? = null,
    val isBuiltin: Boolean,
    val hasApiKey: Boolean,
)

@Serializable
data class ProvidersListResponse(
    val providers: List<ProviderInfoResponse>,
)

@Serializable
data class CreateProviderRequest(
    val name: String,
    val type: String,
    val baseUrl: String,
    val apiKey: String? = null,
    val model: String? = null,
)

@Serializable
data class ProviderTestRequest(
    val baseUrl: String,
    val apiKey: String? = null,
)

@Serializable
data class ProviderTestResponse(
    val success: Boolean,
    val status: Int = 0,
    val message: String = "",
)

@Serializable
data class DeleteProviderResponse(
    val deleted: Boolean,
    val name: String,
)
