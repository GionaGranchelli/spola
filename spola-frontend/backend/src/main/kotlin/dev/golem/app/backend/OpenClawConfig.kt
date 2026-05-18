package dev.spola.app.backend

import kotlinx.serialization.json.*
import java.io.File

object OpenClawConfig {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): JsonObject? {
        val path = defaultOpenClawConfigPath()
        val file = File(path)
        if (!file.exists()) return null
        return runCatching { json.parseToJsonElement(file.readText()).jsonObject }.getOrNull()
    }

    fun getGatewayPort(): Int {
        val config = load() ?: return 18789
        return config["gateway"]?.jsonObject?.get("port")?.jsonPrimitive?.intOrNull ?: 18789
    }

    fun getGatewayToken(): String? {
        val config = load() ?: return null
        return config["gateway"]?.jsonObject?.get("auth")?.jsonObject?.get("token")?.jsonPrimitive?.contentOrNull
    }
}

fun defaultOpenClawConfigPath(): String {
    val home = System.getProperty("user.home")
    return "$home/.openclaw/openclaw.json"
}
