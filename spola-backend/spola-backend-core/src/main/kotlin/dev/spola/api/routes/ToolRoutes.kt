package dev.spola.api

import dev.spola.SpolaConfig
import dev.spola.ToolRegistry
import dev.spola.ToolParameterType
import dev.spola.api.ParameterInfo
import dev.spola.api.ToolDetailResponse
import dev.spola.api.ToolToggleRequest
import dev.spola.api.ToolsResponse
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.request.receive
import kotlinx.serialization.json.JsonPrimitive

fun Route.apiToolRoutes(
    config: SpolaConfig,
    toolRegistry: ToolRegistry,
) {
    get("/tools") {
        call.enforceBearerAuth(config.security.apiKey)
        call.respond(
            ToolsResponse(
                toolRegistry.listAll().map { tool ->
                    tool.toSchemaResponse(toolRegistry.isEnabled(tool.name))
                },
            ),
        )
    }

    post("/tools/{name}/toggle") {
        call.enforceBearerAuth(config.security.apiKey)
        val name = call.requirePathParameter("name", "tool name")
        toolRegistry.get(name).orNotFound { "tool not found: $name" }
        val enabled = toolRegistry.toggleEnabled(name)
        call.respond(mapOf("name" to name, "enabled" to enabled))
    }

    get("/tools/{name}") {
        call.enforceBearerAuth(config.security.apiKey)
        val name = call.requirePathParameter("name", "tool name")
        val tool = toolRegistry.get(name).orNotFound { "tool not found: $name" }
        val enabled = toolRegistry.isEnabled(name)
        call.respond(
            ToolDetailResponse(
                name = tool.name,
                description = tool.description,
                parameters = tool.parameters.map { param ->
                    ParameterInfo(
                        name = param.name,
                        description = param.description,
                        type = when (param.type) {
                            ToolParameterType.STRING -> "string"
                            ToolParameterType.INTEGER -> "integer"
                            ToolParameterType.NUMBER -> "number"
                            ToolParameterType.BOOLEAN -> "boolean"
                            ToolParameterType.ARRAY -> "array"
                            ToolParameterType.OBJECT -> "object"
                            ToolParameterType.ENUM -> "enum"
                        },
                        required = param.required,
                        default = param.defaultValue?.let {
                            when (it) {
                                is String -> JsonPrimitive(it)
                                is Number -> JsonPrimitive(it)
                                is Boolean -> JsonPrimitive(it)
                                else -> JsonPrimitive(it.toString())
                            }
                        },
                    )
                },
                enabled = enabled,
            ),
        )
    }

    put("/tools/{name}/toggle") {
        call.enforceBearerAuth(config.security.apiKey)
        val name = call.requirePathParameter("name", "tool name")
        toolRegistry.get(name).orNotFound { "tool not found: $name" }
        val request = call.receive<ToolToggleRequest>()
        val currently = toolRegistry.isEnabled(name)
        if (request.enabled != currently) {
            toolRegistry.toggleEnabled(name)
        }
        call.respond(mapOf("name" to name, "enabled" to request.enabled))
    }
}
