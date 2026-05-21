package dev.spola

import com.fasterxml.jackson.annotation.JsonIgnore
import dev.spola.config.AgentConfig
import dev.spola.config.CustomProviderConfig
import dev.spola.config.DatabaseConfig
import dev.spola.config.DeliveryConfig
import dev.spola.config.MetricsConfig
import dev.spola.config.ProviderConfig
import dev.spola.config.SecurityConfig
import dev.spola.config.ServerConfig
import dev.spola.config.TtsConfig
import dev.spola.workflow.WorkflowDispatcherConfig
import java.nio.file.Path

typealias CustomProviderDef = CustomProviderConfig

enum class Verbosity {
    NORMAL,
    VERBOSE,
    DEBUG,
}

data class SpolaConfig(
    val database: DatabaseConfig = DatabaseConfig(),
    val delivery: DeliveryConfig = DeliveryConfig(),
    val provider: ProviderConfig = ProviderConfig(),
    val security: SecurityConfig = SecurityConfig(),
    val server: ServerConfig = ServerConfig(),
    val agent: AgentConfig = AgentConfig(),
    val metrics: MetricsConfig = MetricsConfig(),
    val tts: TtsConfig = TtsConfig(),
    val workingDirectory: String = ".",
    val persona: String = "",
    val sessionId: String? = null,
    val verbose: Boolean = false,
    val quiet: Boolean = false,
    val imageGenEnabled: Boolean = false,
    val imageGenProvider: String = "openai",
    val architectMode: ArchitectConfig = ArchitectConfig(),
    val verbosity: Verbosity = Verbosity.NORMAL,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val kanbanWorkflowCooldownSeconds: Long = 30,
    val compressionEnabled: Boolean = true,
    val autoCheckpoint: Boolean = true,
    val jvmIndexAutoRefresh: Boolean = true,
    val pairingToken: String? = null,
    val otelServiceName: String = "spola",
    val pluginsEnabled: Boolean = true,
    val pluginsDir: String = Path.of(System.getProperty("user.home"), ".spola", "plugins").toString(),
    val skillsDir: String = Path.of(System.getProperty("user.home"), ".spola", "skills").toString(),
    val skillsEnabled: Boolean = true,
    val imageGenDefaultSize: String = "1024x1024",
    val imageGenOutputDir: String = Path.of(System.getProperty("user.home"), ".spola", "images").toString(),
    val agentsDir: String = Path.of(System.getProperty("user.home"), ".spola", "agents").toString(),
    val defaultAgentId: String? = null,
    val maxWorkflowNestingDepth: Int = 1,
    val workflowDispatcherConfig: WorkflowDispatcherConfig = WorkflowDispatcherConfig(),
    val workflowsDir: String = Path.of(System.getProperty("user.home"), ".spola", "workflows").toString(),
) {
    @Suppress("LongParameterList")
    constructor(
        model: String = "gpt-4o",
        provider: String = "openai",
        verbosity: Verbosity = Verbosity.NORMAL,
        maxTurns: Int = 25,
        temperature: Double? = null,
        maxTokens: Int? = null,
        workingDirectory: String = ".",
        personaPath: String? = null,
        memoryDbPath: String = "./.spola/memory.db",
        workflowDbPath: String = "./.spola/workflows.db",
        schedulerDbPath: String = "./.spola/scheduler.db",
        kanbanDbPath: String = "./.spola/kanban.db",
        kanbanWorkflowCooldownSeconds: Long = 30,
        compressionEnabled: Boolean = true,
        checkpointDbPath: String = "./.spola/checkpoint.db",
        jvmIndexDbPath: String = "./.spola/jvm-index.db",
        jvmIndexAutoRefresh: Boolean = true,
        autoCheckpoint: Boolean = true,
        apiKey: String? = null,
        pairingToken: String? = null,
        telegramBotToken: String? = null,
        discordBotToken: String? = null,
        emailSmtpHost: String? = null,
        emailSmtpPort: Int = 587,
        emailUsername: String? = null,
        emailPassword: String? = null,
        emailFrom: String? = null,
        ttsProvider: String = "edge",
        elevenlabsApiKey: String? = null,
        elevenlabsVoiceId: String = "21m00Tcm4TlvDq8ikWAM",
        otelEnabled: Boolean = false,
        otelEndpoint: String? = null,
        otelServiceName: String = "spola",
        metricsEnabled: Boolean = true,
        pluginsEnabled: Boolean = true,
        pluginsDir: String = Path.of(System.getProperty("user.home"), ".spola", "plugins").toString(),
        skillsDir: String = Path.of(System.getProperty("user.home"), ".spola", "skills").toString(),
        skillsDbPath: String = Path.of(System.getProperty("user.home"), ".spola", "skills.db").toString(),
        skillsEnabled: Boolean = true,
        imageGenDefaultSize: String = "1024x1024",
        imageGenOutputDir: String = Path.of(System.getProperty("user.home"), ".spola", "images").toString(),
        agentsDir: String = Path.of(System.getProperty("user.home"), ".spola", "agents").toString(),
        agentsDbPath: String = "./.spola/agents.db",
        defaultAgentId: String? = null,
        sessionsDbPath: String = "./.spola/sessions.db",
        architectMode: ArchitectConfig = ArchitectConfig(),
        sessionId: String? = null,
        maxWorkflowNestingDepth: Int = 1,
        workflowDispatcherConfig: WorkflowDispatcherConfig = WorkflowDispatcherConfig(),
        workflowsDir: String = Path.of(System.getProperty("user.home"), ".spola", "workflows").toString(),
        insecure: Boolean = false,
        customProviders: List<CustomProviderConfig> = emptyList(),
    ) : this(
        database = DatabaseConfig(
            memoryDbPath = memoryDbPath,
            checkpointDbPath = checkpointDbPath,
            schedulerDbPath = schedulerDbPath,
            kanbanDbPath = kanbanDbPath,
            workflowsDbPath = workflowDbPath,
            jvmIndexDbPath = jvmIndexDbPath,
            sessionsDbPath = sessionsDbPath,
            agentsDbPath = agentsDbPath,
            skillsDbPath = skillsDbPath,
        ),
        delivery = DeliveryConfig(
            smtpHost = emailSmtpHost.orEmpty(),
            smtpPort = emailSmtpPort,
            smtpUser = emailUsername.orEmpty(),
            smtpPass = emailPassword.orEmpty(),
            fromEmail = emailFrom.orEmpty(),
            telegramToken = telegramBotToken.orEmpty(),
            discordToken = discordBotToken.orEmpty(),
        ),
        provider = ProviderConfig(
            apiKey = apiKey.orEmpty(),
            defaultProvider = provider,
            defaultModel = model,
            customProviders = customProviders,
        ),
        security = SecurityConfig(
            apiKey = apiKey,
            insecure = insecure,
        ),
        agent = AgentConfig(
            sessionId = sessionId,
            personaPath = personaPath,
            maxTurns = maxTurns,
        ),
        metrics = MetricsConfig(
            metricsEnabled = metricsEnabled,
            otelEnabled = otelEnabled,
            otelEndpoint = otelEndpoint.orEmpty(),
        ),
        tts = TtsConfig(
            ttsProvider = ttsProvider,
            elevenlabsApiKey = elevenlabsApiKey,
            elevenlabsVoiceId = elevenlabsVoiceId,
        ),
        workingDirectory = workingDirectory,
        persona = personaPath.orEmpty(),
        sessionId = sessionId,
        architectMode = architectMode,
        verbosity = verbosity,
        temperature = temperature,
        maxTokens = maxTokens,
        kanbanWorkflowCooldownSeconds = kanbanWorkflowCooldownSeconds,
        compressionEnabled = compressionEnabled,
        autoCheckpoint = autoCheckpoint,
        jvmIndexAutoRefresh = jvmIndexAutoRefresh,
        pairingToken = pairingToken,
        otelServiceName = otelServiceName,
        pluginsEnabled = pluginsEnabled,
        pluginsDir = pluginsDir,
        skillsDir = skillsDir,
        skillsEnabled = skillsEnabled,
        imageGenDefaultSize = imageGenDefaultSize,
        imageGenOutputDir = imageGenOutputDir,
        agentsDir = agentsDir,
        defaultAgentId = defaultAgentId,
        maxWorkflowNestingDepth = maxWorkflowNestingDepth,
        workflowDispatcherConfig = workflowDispatcherConfig,
        workflowsDir = workflowsDir,
    )

    @get:JsonIgnore
    val model: String
        get() = provider.defaultModel

    @get:JsonIgnore
    val providerName: String
        get() = provider.defaultProvider

    @get:JsonIgnore
    @Deprecated("Use agent.personaPath", ReplaceWith("agent.personaPath"))
    val personaPath: String?
        get() = agent.personaPath ?: persona.ifBlank { null }

    @get:JsonIgnore
    @Deprecated("Use agent.maxTurns", ReplaceWith("agent.maxTurns"))
    val maxTurns: Int
        get() = agent.maxTurns

    @get:JsonIgnore
    @Deprecated("Use database.memoryDbPath", ReplaceWith("database.memoryDbPath"))
    val memoryDbPath: String
        get() = database.memoryDbPath

    @get:JsonIgnore
    @Deprecated("Use database.workflowsDbPath", ReplaceWith("database.workflowsDbPath"))
    val workflowDbPath: String
        get() = database.workflowsDbPath

    @get:JsonIgnore
    @Deprecated("Use database.schedulerDbPath", ReplaceWith("database.schedulerDbPath"))
    val schedulerDbPath: String
        get() = database.schedulerDbPath

    @get:JsonIgnore
    @Deprecated("Use database.kanbanDbPath", ReplaceWith("database.kanbanDbPath"))
    val kanbanDbPath: String
        get() = database.kanbanDbPath

    @get:JsonIgnore
    @Deprecated("Use database.checkpointDbPath", ReplaceWith("database.checkpointDbPath"))
    val checkpointDbPath: String
        get() = database.checkpointDbPath

    @get:JsonIgnore
    @Deprecated("Use database.jvmIndexDbPath", ReplaceWith("database.jvmIndexDbPath"))
    val jvmIndexDbPath: String
        get() = database.jvmIndexDbPath

    @get:JsonIgnore
    @Deprecated("Use database.sessionsDbPath", ReplaceWith("database.sessionsDbPath"))
    val sessionsDbPath: String
        get() = database.sessionsDbPath

    @get:JsonIgnore
    @Deprecated("Use database.agentsDbPath", ReplaceWith("database.agentsDbPath"))
    val agentsDbPath: String
        get() = database.agentsDbPath

    @get:JsonIgnore
    @Deprecated("Use database.skillsDbPath", ReplaceWith("database.skillsDbPath"))
    val skillsDbPath: String
        get() = database.skillsDbPath

    @get:JsonIgnore
    @Deprecated("Use delivery.telegramToken", ReplaceWith("delivery.telegramToken"))
    val telegramBotToken: String?
        get() = delivery.telegramToken.ifBlank { null }

    @get:JsonIgnore
    @Deprecated("Use delivery.smtpHost", ReplaceWith("delivery.smtpHost"))
    val emailSmtpHost: String?
        get() = delivery.smtpHost.ifBlank { null }

    @get:JsonIgnore
    @Deprecated("Use delivery.smtpPort", ReplaceWith("delivery.smtpPort"))
    val emailSmtpPort: Int
        get() = delivery.smtpPort

    @get:JsonIgnore
    @Deprecated("Use delivery.smtpUser", ReplaceWith("delivery.smtpUser"))
    val emailUsername: String?
        get() = delivery.smtpUser.ifBlank { null }

    @get:JsonIgnore
    @Deprecated("Use delivery.smtpPass", ReplaceWith("delivery.smtpPass"))
    val emailPassword: String?
        get() = delivery.smtpPass.ifBlank { null }

    @get:JsonIgnore
    @Deprecated("Use delivery.fromEmail", ReplaceWith("delivery.fromEmail"))
    val emailFrom: String?
        get() = delivery.fromEmail.ifBlank { null }

    @get:JsonIgnore
    @Deprecated("Use security.apiKey", ReplaceWith("security.apiKey"))
    val apiKey: String?
        get() = security.apiKey ?: provider.apiKey.ifBlank { null }

    @get:JsonIgnore
    @Deprecated("Use security.insecure", ReplaceWith("security.insecure"))
    val insecure: Boolean
        get() = security.insecure

    @get:JsonIgnore
    @Deprecated("Use provider.customProviders", ReplaceWith("provider.customProviders"))
    val customProviders: List<CustomProviderConfig>
        get() = provider.customProviders

    @get:JsonIgnore
    @Deprecated("Use tts.ttsProvider", ReplaceWith("tts.ttsProvider"))
    val ttsProvider: String
        get() = tts.ttsProvider

    @get:JsonIgnore
    @Deprecated("Use tts.elevenlabsApiKey", ReplaceWith("tts.elevenlabsApiKey"))
    val elevenlabsApiKey: String?
        get() = tts.elevenlabsApiKey

    @get:JsonIgnore
    @Deprecated("Use tts.elevenlabsVoiceId", ReplaceWith("tts.elevenlabsVoiceId"))
    val elevenlabsVoiceId: String
        get() = tts.elevenlabsVoiceId

    @get:JsonIgnore
    @Deprecated("Use metrics.metricsEnabled", ReplaceWith("metrics.metricsEnabled"))
    val metricsEnabled: Boolean
        get() = metrics.metricsEnabled

    @get:JsonIgnore
    @Deprecated("Use metrics.otelEnabled", ReplaceWith("metrics.otelEnabled"))
    val otelEnabled: Boolean
        get() = metrics.otelEnabled

    @get:JsonIgnore
    @Deprecated("Use metrics.otelEndpoint", ReplaceWith("metrics.otelEndpoint"))
    val otelEndpoint: String?
        get() = metrics.otelEndpoint.ifBlank { null }
}
