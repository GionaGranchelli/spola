package dev.spola

import com.fasterxml.jackson.annotation.JsonProperty
import dev.spola.workflow.WorkflowDispatcherConfig
import java.nio.file.Path

/**
 * Definition of a custom LLM provider stored in config.yaml.
 * These supplement the built-in providers (openai, anthropic, etc.)
 * and are resolved by ProviderResolver.
 */
data class CustomProviderDef(
    /** Unique name for this provider (e.g. "my-llama", "local-groq"). */
    val name: String,
    /** Provider type: openai, openai-compat, anthropic, google, ollama. */
    val type: String,
    /** Base URL for the API endpoint. */
    val baseUrl: String,
    /** Optional API key. Falls back to env vars if empty. */
    val apiKey: String? = null,
    /** Default model for this provider. */
    val model: String? = null,
)

enum class Verbosity {
    NORMAL,
    VERBOSE,
    DEBUG,
}

/**
 * Configuration for the Spola agent.
 */
data class SpolaConfig(
    val model: String = "gpt-4o",
    val provider: String = "openai",
    val verbosity: Verbosity = Verbosity.NORMAL,
    val maxTurns: Int = 25,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val workingDirectory: String = ".",
    val personaPath: String? = null,
    val memoryDbPath: String = "./.spola/memory.db",
    val workflowDbPath: String = "./.spola/workflows.db",
    val schedulerDbPath: String = "./.spola/scheduler.db",
    val kanbanDbPath: String = "./.spola/kanban.db",
    val kanbanWorkflowCooldownSeconds: Long = 30,
    val compressionEnabled: Boolean = true,
    val checkpointDbPath: String = "./.spola/checkpoint.db",
    val jvmIndexDbPath: String = "./.spola/jvm-index.db",
    val jvmIndexAutoRefresh: Boolean = true,
    val autoCheckpoint: Boolean = true,
    val apiKey: String? = null,
    val pairingToken: String? = null,
    // Delivery configuration
    val telegramBotToken: String? = null,
    val emailSmtpHost: String? = null,
    val emailSmtpPort: Int = 587,
    val emailUsername: String? = null,
    val emailPassword: String? = null,
    val emailFrom: String? = null,
    // TTS configuration
    val ttsProvider: String = "edge",
    val elevenlabsApiKey: String? = null,
    val elevenlabsVoiceId: String = "21m00Tcm4TlvDq8ikWAM",
    // OpenTelemetry observability
    val otelEnabled: Boolean = false,
    val otelEndpoint: String? = null,
    val otelServiceName: String = "spola",
    val metricsEnabled: Boolean = true,
    val pluginsEnabled: Boolean = true,
    val pluginsDir: String = Path.of(System.getProperty("user.home"), ".spola", "plugins").toString(),
    // Skill system configuration
    val skillsDir: String = Path.of(
        System.getProperty("user.home"), ".spola", "skills"
    ).toString(),
    val skillsDbPath: String = Path.of(
        System.getProperty("user.home"), ".spola", "skills.db"
    ).toString(),
    val skillsEnabled: Boolean = true,
    // Image generation configuration
    val imageGenDefaultSize: String = "1024x1024",
    val imageGenOutputDir: String = Path.of(
        System.getProperty("user.home"), ".spola", "images"
    ).toString(),
    // Custom agent configuration
    val agentsDir: String = Path.of(System.getProperty("user.home"), ".spola", "agents").toString(),
    val agentsDbPath: String = "./.spola/agents.db",
    val defaultAgentId: String? = null,
    val sessionsDbPath: String = "./.spola/sessions.db",
    // Architect mode configuration
    val architectMode: ArchitectConfig = ArchitectConfig(),
    // Session and resume configuration
    val sessionId: String? = null,
    val maxWorkflowNestingDepth: Int = 1,
    val workflowDispatcherConfig: WorkflowDispatcherConfig = WorkflowDispatcherConfig(),
    // YAML workflow definitions directory (default: ~/.spola/workflows/)
    val workflowsDir: String = Path.of(
        System.getProperty("user.home"), ".spola", "workflows"
    ).toString(),
    // Security configuration
    val insecure: Boolean = false,
    // Custom providers defined in config.yaml
    @get:JsonProperty("custom_providers")
    val customProviders: List<CustomProviderDef> = emptyList(),
)
