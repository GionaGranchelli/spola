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
        call.enforceBearerAuth(config.security.apiKey, insecure = config.security.insecure)
        call.respond(ConfigResponse.fromConfig(config, configStore.configPath.toString()))
    }

    post("/config/save") {
        call.enforceBearerAuth(config.security.apiKey, insecure = config.security.insecure)
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
        provider = base.provider.copy(
            defaultModel = body.string("model") ?: base.provider.defaultModel,
            defaultProvider = body.string("provider") ?: base.provider.defaultProvider,
        ),
        agent = base.agent.copy(
            maxTurns = body.int("maxTurns") ?: base.agent.maxTurns,
            personaPath = body.string("persona", base.agent.personaPath),
        ),
        temperature = body.double("temperature", base.temperature),
        maxTokens = body.int("maxTokens", base.maxTokens),
        workingDirectory = body.string("workdir") ?: base.workingDirectory,
        persona = body.string("persona", base.persona) ?: "",
        database = base.database.copy(
            memoryDbPath = body.string("memoryDb") ?: base.database.memoryDbPath,
            schedulerDbPath = body.string("schedulerDb") ?: base.database.schedulerDbPath,
            kanbanDbPath = body.string("kanbanDb") ?: base.database.kanbanDbPath,
            checkpointDbPath = body.string("checkpointDb") ?: base.database.checkpointDbPath,
            jvmIndexDbPath = body.string("jvmIndexDb") ?: base.database.jvmIndexDbPath,
            sessionsDbPath = body.string("sessionsDb") ?: base.database.sessionsDbPath,
            agentsDbPath = body.string("agentsDb") ?: base.database.agentsDbPath,
        ),
        kanbanWorkflowCooldownSeconds = body.long("kanbanWorkflowCooldownSeconds") ?: base.kanbanWorkflowCooldownSeconds,
        pluginsDir = body.string("pluginsDir") ?: base.pluginsDir,
        agentsDir = body.string("agentsDir") ?: base.agentsDir,
        security = base.security.copy(
            apiKey = body.string("apiKey", base.security.apiKey),
            insecure = body.bool("insecure") ?: base.security.insecure,
        ),
        pairingToken = body.string("pairingToken", base.pairingToken),
        delivery = base.delivery.copy(
            telegramToken = body.string("telegramBotToken", base.delivery.telegramToken) ?: "",
            smtpHost = body.string("emailSmtpHost", base.delivery.smtpHost) ?: "",
            smtpPort = body.int("emailSmtpPort") ?: base.delivery.smtpPort,
            smtpUser = body.string("emailUsername", base.delivery.smtpUser) ?: "",
            smtpPass = body.string("emailPassword", base.delivery.smtpPass) ?: "",
            fromEmail = body.string("emailFrom", base.delivery.fromEmail) ?: "",
        ),
        tts = base.tts.copy(
            ttsProvider = body.string("ttsProvider") ?: base.tts.ttsProvider,
            elevenlabsApiKey = body.string("elevenlabsApiKey", base.tts.elevenlabsApiKey),
            elevenlabsVoiceId = body.string("elevenlabsVoiceId") ?: base.tts.elevenlabsVoiceId,
        ),
        metrics = base.metrics.copy(
            otelEnabled = body.bool("otelEnabled") ?: base.metrics.otelEnabled,
            otelEndpoint = body.string("otelEndpoint", base.metrics.otelEndpoint) ?: "",
            metricsEnabled = body.bool("metricsEnabled") ?: base.metrics.metricsEnabled,
        ),
        otelServiceName = body.string("otelServiceName") ?: base.otelServiceName,
        pluginsEnabled = body.bool("pluginsEnabled") ?: base.pluginsEnabled,
        compressionEnabled = body.bool("compressionEnabled") ?: base.compressionEnabled,
        autoCheckpoint = body.bool("autoCheckpoint") ?: base.autoCheckpoint,
        jvmIndexAutoRefresh = body.bool("jvmIndexAutoRefresh") ?: base.jvmIndexAutoRefresh,
        defaultAgentId = body.string("defaultAgentId", base.defaultAgentId),
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
