package dev.spola.mcp

import dev.spola.GolemConfig
import dev.spola.ToolRegistry
import dev.spola.checkpoint.CheckpointManager
import dev.spola.api.ApiAuth
import dev.spola.api.InvalidApiKeyException
import dev.spola.api.MissingApiKeyException
import dev.spola.api.respondAuthFailure
import dev.spola.jvm.JvmIndexCoordinator
import dev.spola.memory.SqliteMemoryStore
import dev.spola.scheduler.SqliteGolemJobStore
import io.ktor.server.application.install
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.routing.intercept
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.server.mcp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import java.nio.file.Paths

/**
 * Entry point for running Golem as an MCP server.
 *
 * Unlike the CLI mode, MCP server mode does NOT need an LLM provider.
 * It only registers tools and exposes them via the MCP protocol.
 *
 * Supports two transport modes:
 * - `stdio`: Reads from stdin, writes to stdout. Used by local MCP clients.
 * - `sse`: HTTP Server-Sent Events on the specified port. Used by network MCP clients.
 */
suspend fun runMcpServer(
    port: Int = 8091,
    host: String = "127.0.0.1",
    transport: String = "stdio",
    config: GolemConfig = GolemConfig(),
) {
    // Set current working directory from config
    val workDir = Paths.get(config.workingDirectory).toAbsolutePath().normalize()
    System.setProperty("user.dir", workDir.toString())

    // Set up tool registry + memory store (no LLM provider needed for MCP mode)
    val memoryStore = SqliteMemoryStore(config.memoryDbPath)
    val schedulerStore = config.schedulerDbPath
        .takeIf { it.isNotBlank() }
        ?.let(::SqliteGolemJobStore)
    val checkpointManager = CheckpointManager.fromConfig(config)
    val coordinator = JvmIndexCoordinator(autoRefresh = config.jvmIndexAutoRefresh) { config.workingDirectory }
    val toolRegistry = dev.spola.factory.ToolRegistryFactory.buildMcpToolRegistry(
        config = config,
        memoryStore = memoryStore,
        schedulerStore = schedulerStore,
        checkpointManager = checkpointManager,
        coordinator = coordinator,
    )

    val mcpServer = GolemMcpServer(
        toolRegistry = toolRegistry,
        serverName = "golem-mcp",
        serverVersion = "0.1.0",
    )

    when (transport.lowercase()) {
        "stdio" -> {
            System.err.println("[golem-mcp] Starting MCP stdio server...")
            try {
                mcpServer.startStdio()
            } finally {
                memoryStore.close()
                schedulerStore?.close()
            }
        }
        "sse" -> {
            System.err.println("[golem-mcp] Starting MCP SSE server on $host:$port...")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val server = embeddedServer(CIO, host = host, port = port) {
                    install(SSE)
                    install(io.ktor.server.plugins.statuspages.StatusPages) {
                        exception<MissingApiKeyException> { call, cause ->
                            call.respondAuthFailure(cause)
                        }
                        exception<InvalidApiKeyException> { call, cause ->
                            call.respondAuthFailure(cause)
                        }
                    }
                    routing {
                        route("/mcp") {
                            intercept(ApplicationCallPipeline.Plugins) {
                                val providedApiKey = context.request.queryParameters["apiKey"]
                                    ?: context.request.header("Authorization")?.removePrefix("Bearer ")?.trim()
                                    ?: context.request.header("X-Api-Key")
                                ApiAuth.validateApiKey(config.apiKey, providedApiKey)
                            }
                            mcp {
                                mcpServer.buildServer()
                            }
                        }
                    }
                }
                server.start(wait = true)
            } finally {
                scope.coroutineContext.job.cancelAndJoin()
                memoryStore.close()
                schedulerStore?.close()
            }
        }
        else -> throw IllegalArgumentException("Unsupported MCP transport: $transport. Supported: stdio, sse")
    }
}
