package dev.spola.cli

import dev.spola.GolemConfig
import dev.spola.ToolRegistry
import dev.spola.mcp.McpClientManager
import dev.spola.mcp.McpServerConfig
import kotlinx.coroutines.runBlocking
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import picocli.CommandLine.ParentCommand
import java.util.concurrent.Callable

/**
 * Parent command for managing MCP client connections.
 *
 * Usage: `golem mcp add/list/remove/reconnect`
 */
@Command(
    name = "mcp",
    description = ["Manage MCP client connections to external tool servers"],
    subcommands = [
        McpAddCommand::class,
        McpListCommand::class,
        McpRemoveCommand::class,
        McpReconnectCommand::class,
    ],
)
class McpCommand : Callable<Int> {
    @ParentCommand
    lateinit var root: GolemCli

    override fun call(): Int {
        CommandLine.usage(this, System.out)
        return 0
    }
}

/**
 * `golem mcp add <name> --cmd <command> [--args ...] [--url <sse-url>]`
 */
@Command(
    name = "add",
    description = ["Add and connect an MCP tool server"],
)
class McpAddCommand : Callable<Int> {
    @ParentCommand
    lateinit var mcpCommand: McpCommand

    @Parameters(index = "0", description = ["Unique name for this MCP server"])
    lateinit var name: String

    @Option(
        names = ["--cmd", "--command"],
        description = ["Command to run for stdio transport (e.g., 'node server.js')"],
    )
    var command: String? = null

    @Option(
        names = ["--args"],
        split = ",",
        description = ["Comma-separated arguments for the stdio command"],
    )
    var args: List<String> = emptyList()

    @Option(
        names = ["--url"],
        description = ["SSE endpoint URL (e.g., 'http://localhost:8091/mcp')"],
    )
    var url: String? = null

    @Option(
        names = ["--disabled"],
        description = ["Add the server in disabled state (do not connect)"],
    )
    var disabled: Boolean = false

    override fun call(): Int = runBlocking {
        val transport = when {
            command != null -> "stdio"
            url != null -> "sse"
            else -> {
                System.err.println("Error: specify --cmd <command> for stdio or --url <endpoint> for SSE")
                return@runBlocking 1
            }
        }

        val config = McpServerConfig(
            name = name,
            transport = transport,
            command = command,
            args = args,
            url = url,
            enabled = !disabled,
        )

        val configPath = configPath(mcpCommand.root)
        val manager = McpClientManager(ToolRegistry(), configPath)

        try {
            val resolved = manager.addServer(config)
            println("✅ Added MCP server '${resolved.name}' (${resolved.transport})")
            println("   Enabled: ${resolved.enabled}")
            if (resolved.transport == "stdio") {
                println("   Command: ${resolved.command} ${resolved.args.joinToString(" ")}")
            } else {
                println("   URL: ${resolved.url}")
            }
            0
        } catch (e: Exception) {
            System.err.println("❌ Failed to add MCP server '${name}': ${e.message}")
            1
        }
    }
}

/**
 * `golem mcp list`
 */
@Command(
    name = "list",
    description = ["List all configured MCP servers"],
)
class McpListCommand : Callable<Int> {
    @ParentCommand
    lateinit var mcpCommand: McpCommand

    override fun call(): Int {
        val configPath = configPath(mcpCommand.root)
        val manager = McpClientManager(ToolRegistry(), configPath)
        val configs = manager.loadConfig()

        if (configs.isEmpty()) {
            println("No MCP servers configured.")
            return 0
        }

        println("Configured MCP Servers:")
        println("-".repeat(60))
        for (cfg in configs) {
            val status = if (cfg.enabled) "✅" else "⛔"
            println("$status ${cfg.name}")
            println("   Transport: ${cfg.transport}")
            if (cfg.transport == "stdio") {
                println("   Command: ${cfg.command} ${cfg.args.joinToString(" ")}")
            } else {
                println("   URL: ${cfg.url}")
            }
            println()
        }
        println("${configs.size} server(s)")
        return 0
    }
}

/**
 * `golem mcp remove <name>`
 */
@Command(
    name = "remove",
    aliases = ["rm"],
    description = ["Remove an MCP server connection"],
)
class McpRemoveCommand : Callable<Int> {
    @ParentCommand
    lateinit var mcpCommand: McpCommand

    @Parameters(index = "0", description = ["Name of the MCP server to remove"])
    lateinit var name: String

    override fun call(): Int = runBlocking {
        val configPath = configPath(mcpCommand.root)
        val manager = McpClientManager(ToolRegistry(), configPath)

        try {
            // Load existing configs, remove the matching one, save back
            val configs = manager.loadConfig().toMutableList()
            val removed = configs.removeAll { it.name.equals(name, ignoreCase = true) }
            if (removed) {
                // Save updated list
                for (cfg in configs) {
                    manager.addServer(cfg)
                }
                println("✅ Removed MCP server '${name}'")
                0
            } else {
                System.err.println("MCP server '${name}' not found")
                1
            }
        } catch (e: Exception) {
            System.err.println("❌ Failed to remove MCP server '${name}': ${e.message}")
            1
        }
    }
}

/**
 * `golem mcp reconnect <name>`
 */
@Command(
    name = "reconnect",
    description = ["Reconnect to a disconnected MCP server"],
)
class McpReconnectCommand : Callable<Int> {
    @ParentCommand
    lateinit var mcpCommand: McpCommand

    @Parameters(index = "0", description = ["Name of the MCP server to reconnect"])
    lateinit var name: String

    override fun call(): Int = runBlocking {
        val configPath = configPath(mcpCommand.root)
        val manager = McpClientManager(ToolRegistry(), configPath)

        try {
            val success = manager.reconnectServer(name)
            if (success) {
                println("✅ Reconnected to MCP server '${name}'")
                0
            } else {
                System.err.println("❌ Failed to reconnect to MCP server '${name}'")
                1
            }
        } catch (e: Exception) {
            System.err.println("❌ Reconnection failed: ${e.message}")
            1
        }
    }
}

/** Derive the MCP config path from the root CLI working directory. */
private fun configPath(root: GolemCli): String {
    return java.nio.file.Path.of(root.workdir, ".golem", "mcp-servers.json").toString()
}
