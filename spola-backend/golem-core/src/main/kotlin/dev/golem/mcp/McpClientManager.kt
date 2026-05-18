package dev.spola.mcp

import dev.spola.Tool
import dev.spola.ToolParameterType
import dev.spola.ToolRegistry
import dev.spola.ToolResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.client.SseClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration for an MCP server connection.
 */
@Serializable
data class McpServerConfig(
    val name: String,
    val transport: String,
    val command: String? = null,
    val args: List<String> = emptyList(),
    val url: String? = null,
    val enabled: Boolean = true,
)

/**
 * Tracks a single active MCP connection: the client, the spawned process (for stdio),
 * the tools it provides, and its current status.
 */
class McpConnection(
    val config: McpServerConfig,
    val client: Client,
    val process: Process? = null,
    val httpClient: HttpClient? = null,
) {
    @Volatile
    var connected: Boolean = true
        internal set

    val tools: MutableList<McpToolRegistration> = mutableListOf()
}

/**
 * Records a tool that was registered into the ToolRegistry from a remote MCP server.
 */
data class McpToolRegistration(
    val localName: String,
    val remoteName: String,
)

/**
 * Manages MCP client connections. Each configured MCP server is connected to,
 * its tools are discovered and registered into a shared [ToolRegistry] with a
 * namespaced prefix (`mcp_{serverName}_{toolName}`) to avoid naming conflicts.
 */
class McpClientManager(
    private val toolRegistry: ToolRegistry,
    private val configPath: String = System.getProperty("user.home") + "/.golem/mcp-servers.json",
) {
    private val logger = LoggerFactory.getLogger(McpClientManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connections = ConcurrentHashMap<String, McpConnection>()
    private val clientName = "golem-mcp-client"
    private val clientVersion = "0.1.0"
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Add an MCP server, connect to it, and register its tools.
     * Returns the config (with any defaults filled in).
     */
    suspend fun addServer(config: McpServerConfig): McpServerConfig {
        val resolved = config.copy(
            name = config.name.trim().lowercase(),
            transport = config.transport.trim().lowercase(),
            command = config.command?.trim()?.ifBlank { null },
            url = config.url?.trim()?.ifBlank { null },
        )
        require(resolved.name.isNotBlank()) { "Server name must not be blank" }
        require(resolved.transport in listOf("stdio", "sse")) {
            "Unsupported transport '${resolved.transport}'. Use 'stdio' or 'sse'."
        }
        when (resolved.transport) {
            "stdio" -> require(resolved.command != null) {
                "command is required for stdio transport"
            }
            "sse" -> require(resolved.url != null) {
                "url is required for sse transport"
            }
        }

        if (connections.containsKey(resolved.name)) {
            logger.warn("Server '${resolved.name}' already exists; removing old connection first")
            removeServer(resolved.name)
        }

        val connection = connectToServer(resolved)
        connections[resolved.name] = connection

        // Discover and register tools
        registerServerTools(resolved.name, connection)

        saveConfig()
        return resolved
    }

    /**
     * Remove an MCP server: disconnect, unregister tools, clean up.
     */
    suspend fun removeServer(name: String): Boolean {
        val conn = connections.remove(name.lowercase()) ?: return false
        disconnect(conn)
        unregisterServerTools(conn)
        saveConfig()
        return true
    }

    /**
     * List all known MCP server configs (from the config file, not just active connections).
     */
    fun listServers(): List<McpServerConfig> {
        return connections.values.map { it.config }
    }

    /**
     * Reconnect a previously-disconnected server.
     */
    suspend fun reconnectServer(name: String): Boolean {
        val existing = connections[name.lowercase()] ?: return false
        if (existing.connected) return true

        return try {
            val newConn = connectToServer(existing.config)
            connections[name.lowercase()] = newConn
            registerServerTools(existing.config.name, newConn)
            true
        } catch (e: Exception) {
            logger.error("Failed to reconnect server '{}': {}", name, e.message)
            false
        }
    }

    /**
     * Disconnect all servers and clean up.
     */
    fun shutdown() {
        val snapshot = connections.entries.toList()
        connections.clear()
        snapshot.forEach { (_, conn) -> disconnect(conn) }
    }

    // ------------------------------------------------------------------
    // Config persistence
    // ------------------------------------------------------------------

    /**
     * Save the current server configs to the JSON config file.
     */
    fun saveConfig() {
        try {
            val file = File(configPath)
            file.parentFile.mkdirs()
            val configs = connections.values.map { it.config }
            file.writeText(json.encodeToString(configs))
        } catch (e: Exception) {
            logger.error("Failed to save MCP config to {}: {}", configPath, e.message)
        }
    }

    /**
     * Load server configs from the JSON config file.
     */
    fun loadConfig(): List<McpServerConfig> {
        return try {
            val file = File(configPath)
            if (!file.exists()) return emptyList()
            val text = file.readText().trim()
            if (text.isBlank()) return emptyList()
            json.decodeFromString<List<McpServerConfig>>(text)
        } catch (e: Exception) {
            logger.error("Failed to load MCP config from {}: {}", configPath, e.message)
            emptyList()
        }
    }

    /**
     * Connect all servers from the config file.
     * Returns the list of successfully connected configs.
     */
    suspend fun connectAllFromConfig(): List<McpServerConfig> {
        val configs = loadConfig().filter { it.enabled }
        val connected = mutableListOf<McpServerConfig>()
        for (cfg in configs) {
            try {
                addServer(cfg)
                connected.add(cfg)
            } catch (e: Exception) {
                logger.error("Failed to connect MCP server '{}': {}", cfg.name, e.message)
            }
        }
        return connected
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    private suspend fun connectToServer(config: McpServerConfig): McpConnection {
        val client = Client(
            clientInfo = Implementation(name = clientName, version = clientVersion),
            options = ClientOptions(),
        )

        return when (config.transport.lowercase()) {
            "stdio" -> connectStdio(config, client)
            "sse" -> connectSse(config, client)
            else -> throw IllegalArgumentException("Unsupported transport: ${config.transport}")
        }
    }

    private suspend fun connectStdio(config: McpServerConfig, client: Client): McpConnection {
        require(config.command != null) { "command is required for stdio transport" }

        val processBuilder = ProcessBuilder(listOfNotNull(config.command) + config.args)
            .redirectErrorStream(false)

        val process = processBuilder.start()

        val input = process.inputStream.asSource().buffered()   // read from process stdout
        val output = process.outputStream.asSink().buffered()   // write to process stdin
        val error = process.errorStream.asSource().buffered()   // read from process stderr

        val transport = StdioClientTransport(
            input = input,
            output = output,
            error = error,
        )

        client.connect(transport)

        return McpConnection(
            config = config,
            client = client,
            process = process,
        )
    }

    private suspend fun connectSse(config: McpServerConfig, client: Client): McpConnection {
        val baseUrl = config.url ?: throw IllegalArgumentException("url is required for sse transport")
        val httpClient = HttpClient(CIO)
        try {
            val transport = SseClientTransport(
                client = httpClient,
                urlString = baseUrl,
                reconnectionTime = 30.seconds,
            )
            client.connect(transport)
        } catch (e: Exception) {
            httpClient.close()
            throw e
        }
        return McpConnection(
            config = config,
            client = client,
            httpClient = httpClient,
        )
    }

    private suspend fun registerServerTools(serverName: String, connection: McpConnection) {
        try {
            val listResult = connection.client.listTools()
            for (tool in listResult.tools) {
                val localName = "mcp_${serverName}_${tool.name}"
                val golemTool = Tool(
                    name = localName,
                    description = "[MCP:$serverName] ${tool.description ?: tool.name}",
                    parameters = convertInputSchema(tool.inputSchema),
                    execute = { args ->
                        executeMcpTool(connection, tool.name, args)
                    },
                )
                toolRegistry.register(golemTool)
                connection.tools.add(McpToolRegistration(localName, tool.name))
                logger.info("Registered MCP tool '{}' from server '{}'", localName, serverName)
            }
        } catch (e: Exception) {
            logger.error("Failed to list tools from server '{}': {}", serverName, e.message)
        }
    }

    private fun unregisterServerTools(connection: McpConnection) {
        for (reg in connection.tools) {
            toolRegistry.unregister(reg.localName)
            logger.info("Unregistered MCP tool '{}'", reg.localName)
        }
        connection.tools.clear()
    }

    private suspend fun executeMcpTool(
        connection: McpConnection,
        toolName: String,
        args: Map<String, Any>,
    ): ToolResult {
        return try {
            if (!connection.connected) {
                return ToolResult.fail("MCP server '${connection.config.name}' is disconnected")
            }
            val result = connection.client.callTool(toolName, args)
            ToolResult.ok(formatToolResult(result))
        } catch (e: Exception) {
            // Attempt auto-reconnect once
            if (!connection.connected) {
                try {
                    reconnectServer(connection.config.name)
                } catch (_: Exception) { }
            }
            ToolResult.fail("MCP tool '$toolName' error: ${e.message}")
        }
    }

    private fun formatToolResult(result: CallToolResult): String {
        val parts = result.content.map { content ->
            when (content) {
                is io.modelcontextprotocol.kotlin.sdk.types.TextContent -> content.text
                else -> content.toString()
            }
        }
        return parts.joinToString("\n")
    }

    private fun disconnect(connection: McpConnection) {
        try {
            kotlinx.coroutines.runBlocking {
                connection.client.close()
            }
        } catch (e: Exception) {
            logger.warn("Error closing MCP client: {}", e.message)
        }
        try {
            connection.process?.destroyForcibly()
        } catch (e: Exception) {
            logger.warn("Error destroying MCP process: {}", e.message)
        }
        try {
            connection.httpClient?.close()
        } catch (e: Exception) {
            logger.warn("Error closing MCP SSE HTTP client: {}", e.message)
        }
        connection.connected = false
    }

    /**
     * Convert an MCP tool's JSON Schema input schema into Golem's ToolParameter list.
     */
    private fun convertInputSchema(schema: io.modelcontextprotocol.kotlin.sdk.types.ToolSchema): List<dev.spola.ToolParameter> {
        val params = mutableListOf<dev.spola.ToolParameter>()

        // The schema.properties is a JsonObject with property-name -> schema entries
        val properties = schema.properties ?: buildJsonObject { }
        val required = schema.required ?: emptyList()

        for ((name, propSchema) in properties) {
            val type = when {
                propSchema is JsonObject -> propSchema["type"]?.jsonPrimitive?.content
                else -> null
            }
            val description = when {
                propSchema is JsonObject -> propSchema["description"]?.jsonPrimitive?.content ?: ""
                else -> ""
            }

            params.add(
                dev.spola.ToolParameter(
                    name = name,
                    description = description,
                    type = when (type) {
                        "integer" -> ToolParameterType.INTEGER
                        "boolean" -> ToolParameterType.BOOLEAN
                        else -> ToolParameterType.STRING
                    },
                    required = name in required,
                ),
            )
        }

        return params
    }
}
