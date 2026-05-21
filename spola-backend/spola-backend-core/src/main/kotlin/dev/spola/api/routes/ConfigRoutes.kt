package dev.spola.api

import dev.spola.ArchitectConfig
import dev.spola.SpolaConfig
import dev.spola.api.ConfigResponse
import dev.spola.api.ConfigSaveResponse
import dev.spola.config.SpolaConfigFileStore
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive

fun Route.apiConfigRoutes(
    config: SpolaConfig,
    configStore: SpolaConfigFileStore,
) {
    get("/config") {
        call.enforceBearerAuth(config.apiKey)
        call.respond(ConfigResponse.fromConfig(config, configStore.configPath.toString()))
    }

    post("/config/save") {
        call.enforceBearerAuth(config.apiKey)
        val body = call.receive<JsonObject>()
        val savedConfig = mergeConfig(configStore.load(), body)
        configStore.save(savedConfig)
        call.respond(
            HttpStatusCode.OK,
            ConfigSaveResponse(
                success = true,
                effectiveConfigPath = configStore.configPath.toString(),
                config = ConfigResponse.fromConfig(savedConfig, configStore.configPath.toString()),
            ),
        )
    }
}

private fun mergeConfig(base: SpolaConfig, body: JsonObject): SpolaConfig {
    return base.copy(
        model = body.string("model") ?: base.model,
        provider = body.string("provider") ?: base.provider,
        maxTurns = body.int("maxTurns") ?: base.maxTurns,
        temperature = body.double("temperature", base.temperature),
        maxTokens = body.int("maxTokens", base.maxTokens),
        workingDirectory = body.string("workdir") ?: base.workingDirectory,
        personaPath = body.string("persona", base.personaPath),
        memoryDbPath = body.string("memoryDb") ?: base.memoryDbPath,
        schedulerDbPath = body.string("schedulerDb") ?: base.schedulerDbPath,
        kanbanDbPath = body.string("kanbanDb") ?: base.kanbanDbPath,
        kanbanWorkflowCooldownSeconds = body.long("kanbanWorkflowCooldownSeconds") ?: base.kanbanWorkflowCooldownSeconds,
        checkpointDbPath = body.string("checkpointDb") ?: base.checkpointDbPath,
        jvmIndexDbPath = body.string("jvmIndexDb") ?: base.jvmIndexDbPath,
        sessionsDbPath = body.string("sessionsDb") ?: base.sessionsDbPath,
        pluginsDir = body.string("pluginsDir") ?: base.pluginsDir,
        agentsDir = body.string("agentsDir") ?: base.agentsDir,
        agentsDbPath = body.string("agentsDb") ?: base.agentsDbPath,
        apiKey = body.string("apiKey", base.apiKey),
        pairingToken = body.string("pairingToken", base.pairingToken),
        telegramBotToken = body.string("telegramBotToken", base.telegramBotToken),
        emailSmtpHost = body.string("emailSmtpHost", base.emailSmtpHost),
        emailSmtpPort = body.int("emailSmtpPort") ?: base.emailSmtpPort,
        emailUsername = body.string("emailUsername", base.emailUsername),
        emailPassword = body.string("emailPassword", base.emailPassword),
        emailFrom = body.string("emailFrom", base.emailFrom),
        ttsProvider = body.string("ttsProvider") ?: base.ttsProvider,
        elevenlabsApiKey = body.string("elevenlabsApiKey", base.elevenlabsApiKey),
        elevenlabsVoiceId = body.string("elevenlabsVoiceId") ?: base.elevenlabsVoiceId,
        otelEnabled = body.bool("otelEnabled") ?: base.otelEnabled,
        otelEndpoint = body.string("otelEndpoint", base.otelEndpoint),
        otelServiceName = body.string("otelServiceName") ?: base.otelServiceName,
        metricsEnabled = body.bool("metricsEnabled") ?: base.metricsEnabled,
        pluginsEnabled = body.bool("pluginsEnabled") ?: base.pluginsEnabled,
        compressionEnabled = body.bool("compressionEnabled") ?: base.compressionEnabled,
        autoCheckpoint = body.bool("autoCheckpoint") ?: base.autoCheckpoint,
        jvmIndexAutoRefresh = body.bool("jvmIndexAutoRefresh") ?: base.jvmIndexAutoRefresh,
        defaultAgentId = body.string("defaultAgentId", base.defaultAgentId),
        insecure = body.bool("insecure") ?: base.insecure,
        architectMode = mergeArchitectConfig(base.architectMode, body),
    )
}

private fun mergeArchitectConfig(
    base: ArchitectConfig,
    body: JsonObject,
): ArchitectConfig {
    return base.copy(
        enabled = body.bool("architectEnabled") ?: base.enabled,
        architectModel = body.string("architectModel") ?: base.architectModel,
        architectProvider = body.string("architectProvider") ?: base.architectProvider,
        editorModel = body.string("editorModel") ?: base.editorModel,
        editorProvider = body.string("editorProvider") ?: base.editorProvider,
    )
}

private fun JsonObject.string(key: String): String? = when {
    !containsKey(key) -> null
    this[key] is JsonNull -> null
    else -> this[key]?.jsonPrimitive?.content
}

private fun JsonObject.string(key: String, default: String?): String? = when {
    !containsKey(key) -> default
    this[key] is JsonNull -> null
    else -> this[key]?.jsonPrimitive?.content
}

private fun JsonObject.int(key: String): Int? = when {
    !containsKey(key) || this[key] is JsonNull -> null
    else -> this[key]?.jsonPrimitive?.intOrNull
}

private fun JsonObject.int(key: String, default: Int?): Int? = when {
    !containsKey(key) -> default
    this[key] is JsonNull -> null
    else -> this[key]?.jsonPrimitive?.intOrNull
}

private fun JsonObject.long(key: String): Long? = when {
    !containsKey(key) || this[key] is JsonNull -> null
    else -> this[key]?.jsonPrimitive?.longOrNull
}

private fun JsonObject.double(key: String, default: Double?): Double? = when {
    !containsKey(key) -> default
    this[key] is JsonNull -> null
    else -> this[key]?.jsonPrimitive?.doubleOrNull
}

private fun JsonObject.bool(key: String): Boolean? = when {
    !containsKey(key) || this[key] is JsonNull -> null
    else -> this[key]?.jsonPrimitive?.booleanOrNull
}
