package dev.spola.mcp

import dev.spola.SpolaVersion
import dev.spola.Tool
import dev.spola.ToolParameterType
import dev.spola.ToolRegistry
import dev.spola.ToolResult
import dev.spola.toTypeString
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.Tool as McpTool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import dev.spola.util.jsonElementToUntypedValue
import dev.spola.util.jsonValueToElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.long
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered

/**
 * MCP (Model Context Protocol) server that exposes all Spola tools as MCP tools.
 *
 * Any MCP client (Hermes, Claude Code, Codex CLI) can discover and call Spola's
 * built-in tools through stdio or SSE transport.
 */
class SpolaMcpServer(
    private val toolRegistry: ToolRegistry,
    private val serverName: String = "spola-backend-mcp",
    private val serverVersion: String = SpolaVersion.VERSION,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: Server? = null

    /**
     * Start the MCP server with stdio transport.
     * Reads from stdin, writes to stdout — compatible with Hermes, Claude Code, etc.
     * Returns when stdin closes or the transport disconnects.
     */
    suspend fun startStdio() {
        val mcpServer = buildServer()
        server = mcpServer

        val transport = StdioServerTransport(
            inputStream = System.`in`.asSource().buffered(),
            outputStream = System.out.asSink().buffered(),
        )
        mcpServer.createSession(transport)

        // Keep running until stdin closes or scope is cancelled
        try {
            while (scope.isActive) {
                kotlinx.coroutines.delay(1000)
            }
        } catch (_: kotlinx.coroutines.CancellationException) {
            // Normal shutdown
        }
    }

    /**
     * Build the MCP server instance with all tools registered, without connecting transport.
     * Useful for testing or custom transport setup.
     */
    fun buildServer(): Server {
        val capabilities = ServerCapabilities(
            tools = ServerCapabilities.Tools(listChanged = false),
        )
        val mcpServer = Server(
            serverInfo = Implementation(name = serverName, version = serverVersion),
            options = ServerOptions(capabilities = capabilities),
        )

        // Register each Spola tool as an MCP tool
        for (spolaTool in toolRegistry.list()) {
            mcpServer.addTool(
                tool = McpTool(
                    name = spolaTool.name,
                    description = spolaTool.description,
                    inputSchema = buildToolSchema(spolaTool),
                ),
            ) { request ->
                val result = executeSpolaTool(spolaTool, request.arguments)
                CallToolResult(
                    content = listOf(io.modelcontextprotocol.kotlin.sdk.types.TextContent(result.output)),
                    isError = !result.success,
                )
            }
        }

        return mcpServer
    }

    /**
     * Execute a Spola tool with properly decoded arguments.
     * Handles the MCP SDK's JsonElement → typed values conversion.
     */
    private suspend fun executeSpolaTool(
        tool: Tool,
        arguments: JsonElement?,
    ): ToolResult {
        val args = mutableMapOf<String, Any>()
        if (arguments is kotlinx.serialization.json.JsonObject) {
            for ((key, value) in arguments) {
                args[key] = jsonElementToValue(value)
            }
        }
        return tool.execute(args)
    }

    /**
     * Convert a kotlinx.serialization JsonElement to the appropriate Kotlin type.
     */
    private fun jsonElementToValue(element: JsonElement): Any =
        jsonElementToUntypedValue(element) ?: "null"

    /**
     * Convert Spola tool parameters to MCP JSON Schema.
     */
    private fun buildToolSchema(spolaTool: Tool): ToolSchema {
        val properties = buildJsonObject {
            for (param in spolaTool.parameters) {
                putJsonObject(param.name) {
                    put("type", JsonPrimitive(param.type.toTypeString()))
                    put("description", JsonPrimitive(param.description))
                    if (param.type == ToolParameterType.ARRAY) {
                        put("items", buildJsonObject { })
                    }
                    if (param.type == ToolParameterType.OBJECT) {
                        put("additionalProperties", JsonPrimitive(true))
                    }
                    if (param.type == ToolParameterType.ENUM && param.enumValues.isNotEmpty()) {
                        put("enum", JsonArray(param.enumValues.map(::JsonPrimitive)))
                    }
                    if (param.defaultValue != null) {
                        put("default", jsonValueToElement(param.defaultValue))
                    }
                }
            }
        }

        return ToolSchema(
            properties = properties,
            required = spolaTool.parameters
                .filter { it.required }
                .map { it.name }
                .ifEmpty { null },
        )
    }
}
