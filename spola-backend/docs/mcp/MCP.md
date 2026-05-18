# Golem MCP (Model Context Protocol)

## Overview

Golem provides **bidirectional MCP support** — it can act as both an **MCP Server**
(exposing its tools to any MCP client) and an **MCP Client** (consuming tools from
external MCP servers and merging them into Golem's tool registry).

The Model Context Protocol (MCP) is an open standard that lets AI applications
discover and call tools across different systems. Golem's implementation uses
the official Kotlin MCP SDK.

---

## MCP Server Mode

Expose all of Golem's built-in tools (filesystem, shell, web search, memory, etc.)
to any MCP client — Claude Desktop, Cursor, VS Code extensions, Codex CLI, and
any other MCP-aware application.

### Starting the MCP Server

```bash
# stdio transport (default) — for local MCP clients
golem --mcp

# Or using the mcp subcommand
golem mcp

# SSE transport on port 8091 (for network clients)
golem --mcp --mcp-transport sse --mcp-port 8091

# SSE with API key authentication
golem --mcp --mcp-transport sse --api-key my-secret

# Bind to all interfaces for remote SSE access
golem --mcp --mcp-transport sse --mcp-port 8091 --host 0.0.0.0 --api-key my-secret
```

### Transport Modes

| Mode | CLI Flag | Default | Use Case |
|------|----------|---------|----------|
| stdio | `--mcp-transport stdio` | Yes | Local clients (Claude Desktop, Cursor) |
| sse | `--mcp-transport sse` | No | Network clients (HTTP on port 8091) |

### Key Facts

- **No LLM provider needed** — MCP server mode only registers and exposes tools.
  No API keys for OpenAI/Anthropic/etc. are required.
- **Working directory** — Honors the configured `workingDirectory` from config.
- **All tools are exposed** — Every tool in Golem's registry is automatically
  serialized to MCP's tool format with JSON Schema parameters.

### stdio Transport

The stdio transport reads MCP JSON-RPC messages from stdin and writes responses
to stdout. This is the standard transport used by desktop MCP clients.

#### Connecting from Claude Desktop

Add to your `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "golem": {
      "command": "/path/to/golem-cli",
      "args": ["--mcp"],
      "env": {
        "GOLEM_WORKING_DIR": "/home/user/my-project"
      }
    }
  }
}
```

#### Connecting from Cursor

In Cursor's MCP server settings:

```
Name: Golem
Type: command
Command: /path/to/golem-cli --mcp
```

#### Connecting from VS Code (GitHub Copilot)

Configure in VS Code's MCP settings:

```json
{
  "mcp": {
    "servers": {
      "golem": {
        "command": "/path/to/golem-cli",
        "args": ["--mcp"]
      }
    }
  }
}
```

### SSE Transport

The SSE transport runs an HTTP server (default port `8091`) that uses
Server-Sent Events for MCP communication. This allows network clients to
connect to Golem's MCP server remotely.

```
http://{host}:{port}/mcp
```

#### SSE Authentication

When `--api-key` is set, the SSE endpoint enforces authentication via one of:

1. **Query parameter**: `?apiKey=your-key`
2. **Authorization header**: `Authorization: Bearer your-key`
3. **X-Api-Key header**: `X-Api-Key: your-key`

If no API key is configured, the SSE endpoint is open.

#### Connecting to SSE Remotely

MCP clients connect to `http://{host}:8091/mcp` with the `apiKey` query parameter:

```
http://192.168.1.100:8091/mcp?apiKey=my-secret
```

### Tools Exposed

Every tool registered in Golem's tool registry is automatically available as
an MCP tool. The full set includes (but is not limited to):

| Tool | Description |
|------|-------------|
| `read_file` | Read file contents |
| `write_file` | Write content to a file |
| `edit_file` | Apply targeted edits to a file |
| `search_files` | Search file contents or find files |
| `shell` | Execute shell commands |
| `web_search` | Search the web (DuckDuckGo) |
| `web_fetch` | Fetch web page content |
| `memory_save` | Save to persistent memory |
| `memory_search` | Search persistent memory |
| `git_diff` | Show git diff |
| `git_commit` | Create a git commit |
| `git_status` | Show git status |
| `git_log` | Show git commit log |
| `jvm_project_overview` | Analyze a JVM project |
| `jvm_symbol_search` | Search JVM symbols |
| `jvm_file_outline` | Get file structure |
| `jvm_context_pack` | Pack relevant context |
| `jvm_dependency_trace` | Trace dependencies |
| `jvm_change_impact` | Analyze change impact |
| `jvm_failure_explain` | Explain build failures |
| `telegram_send` | Send Telegram message |
| `email_send` | Send email |

Plus any custom or MCP client-registered tools.

---

## MCP Client Mode

Golem can connect to **external MCP servers**, discover their tools, and register
them into its own tool registry with namespaced names (`mcp_{serverName}_{toolName}`).
This allows Golem's agents to use tools from any MCP server seamlessly.

### Configuration

MCP server connections are configured via a JSON file at:

**`~/.golem/mcp-servers.json`**

```json
[
  {
    "name": "filesystem",
    "transport": "stdio",
    "command": "npx",
    "args": ["-y", "@modelcontextprotocol/server-filesystem", "/home/user"],
    "enabled": true
  },
  {
    "name": "github",
    "transport": "stdio",
    "command": "npx",
    "args": ["-y", "@modelcontextprotocol/server-github"],
    "enabled": true
  },
  {
    "name": "database",
    "transport": "stdio",
    "command": "/usr/local/bin/mcp-db-server",
    "args": [],
    "enabled": false
  }
]
```

### Configuration Fields

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `name` | string | Yes | Unique server name (lowercased, trimmed) |
| `transport` | string | Yes | `"stdio"` or `"sse"` (SSE not yet supported) |
| `command` | string | For stdio | The executable command to run |
| `args` | string[] | No | Arguments for the command |
| `url` | string | For SSE | SSE endpoint URL (future) |
| `enabled` | boolean | No | Whether to auto-connect (default: true) |

### How It Works

1. **Startup** — Golem reads `~/.golem/mcp-servers.json`
2. **Connect** — For each enabled server, Golem spawns the command process and
   connects via the MCP stdio protocol
3. **Discover** — Golem calls the server's `tools/list` RPC to discover available tools
4. **Register** — Each tool is registered in Golem's registry with the name
   `mcp_{serverName}_{toolName}` (e.g., `mcp_filesystem_read_file`)
5. **Use** — Golem's agents can now call these tools like any built-in tool

Tool description is prefixed with `[MCP:serverName]` for easy identification.

### Adding an MCP Server at Runtime

MCP servers can be added dynamically using Golem's internal API:

```kotlin
mcpClientManager.addServer(
    McpServerConfig(
        name = "my-server",
        transport = "stdio",
        command = "/path/to/server-binary",
        args = listOf("--flag", "value"),
    )
)
```

### Removing an MCP Server

Removing a server disconnects the process, unregisters all its tools from the
registry, and saves the updated configuration:

```kotlin
mcpClientManager.removeServer("my-server")
```

### Auto-Reconnect

If a tool call fails because the MCP server disconnected, Golem attempts a
single automatic reconnect before returning the error to the agent.

### Tool Name Scoping

To avoid naming conflicts between different MCP servers, each external tool is
prefixed with `mcp_{serverName}_`. For example:

| MCP Server | Remote Tool | Local Name |
|------------|-------------|------------|
| filesystem | read_file | `mcp_filesystem_read_file` |
| filesystem | write_file | `mcp_filesystem_write_file` |
| github | create_issue | `mcp_github_create_issue` |
| database | query | `mcp_database_query` |

---

## Architecture

### GolemMcpServer (Server Mode)

Located at: `golem-core/src/main/kotlin/dev/golem/mcp/GolemMcpServer.kt`

The server mode follows this flow:

```
MCP Client (Claude Desktop, Cursor, etc.)
       │
       ▼  stdin/stdout (stdio) or HTTP/SSE (sse)
┌──────────────────────────────┐
│     GolemMcpServer           │
│  ┌──────────────────────┐    │
│  │   MCP Server SDK     │    │
│  │  (Server + Transport)│    │
│  └──────────┬───────────┘    │
│             │                │
│  ┌──────────▼───────────┐    │
│  │  Tool Registry        │    │
│  │  (list → register)    │    │
│  └──────────┬───────────┘    │
│             │                │
│  ┌──────────▼───────────┐    │
│  │  Golem Tools          │    │
│  │  (read_file, shell,   │    │
│  │   web_search, ...)    │    │
│  └──────────────────────┘    │
└──────────────────────────────┘
```

Key internals:

- **`buildServer()`** — Creates the MCP `Server` instance with `ServerCapabilities(tools = ...)`.
  Iterates through the tool registry and calls `mcpServer.addTool()` for each tool.
- **`buildToolSchema(golemTool)`** — Converts Golem's `ToolParameter` list to MCP's
  `ToolSchema` JSON Schema format. Handles string/integer/boolean types, required flags,
  and default values.
- **`executeGolemTool(tool, arguments)`** — Receives `JsonElement` arguments from the MCP
  SDK, converts them to Kotlin types via `jsonElementToValue()`, and calls `tool.execute()`.
- **`jsonElementToValue(element)`** — Recursively converts `JsonPrimitive` values:
  strings stay strings, numbers become Int/Long, booleans are detected by content.
- **`startStdio()`** — Uses `StdioServerTransport` wrapping `System.in`/`System.out`
  with the kotlinx-io buffered sink/source API. Runs until stdin closes.

### McpClientManager (Client Mode)

Located at: `golem-core/src/main/kotlin/dev/golem/mcp/McpClientManager.kt`

The client mode follows this flow:

```
Golem Agent / Tool Registry
       │
       │  mcp_filesystem_read_file, mcp_github_create_issue, ...
       │
       ▼
┌──────────────────────────────┐
│     McpClientManager         │
│  ┌──────────────────────────┐│
│  │  Connection Map          ││
│  │  (serverName → McpConn)  ││
│  └──────────────────────────┘│
│            │                 │
│    ┌───────┴────────┐        │
│    │ filesystem     │ github │
│    │ (stdio proc)   │ (stdio)│
│    └───────┬────────┘        │
│            ▼                 │
│    ┌──────────────────┐      │
│    │ MCP Client SDK   │      │
│    │ (Client + Transp)│      │
│    └──────────────────┘      │
└──────────────────────────────┘
       │
       ▼
External MCP Servers (stdio subprocesses)
```

Key internals:

- **`McpServerConfig`** — Serializable data class with `name`, `transport`, `command`,
  `args`, `url`, `enabled`.
- **`McpConnection`** — Tracks a live connection: the `Client` instance, the spawned
  `Process`, the `connected` flag, and the list of registered `McpToolRegistration`s.
- **`addServer(config)`** — Creates a new client connection via `connectToServer()`,
  which spawns the process and creates a `StdioClientTransport`. Then calls
  `registerServerTools()` to list and register all tools.
- **`registerServerTools()`** — Calls `client.listTools()`, creates a `Tool` for each
  with the namespaced name `mcp_{serverName}_{toolName}`, and registers it in the
  shared `ToolRegistry`.
- **`convertInputSchema()`** — Converts MCP's JSON Schema format back to Golem's
  `ToolParameter` list. Handles `string`, `integer`, `boolean` types and `required` arrays.
- **`executeMcpTool()`** — Calls `connection.client.callTool()` on the remote server.
  If the connection is lost, attempts one auto-reconnect before failing.
- **`saveConfig()` / `loadConfig()`** — Persists the server list to
  `~/.golem/mcp-servers.json` so connections survive restarts.
- **`connectAllFromConfig()`** — Called at startup; iterates enabled configs,
  connects each, and returns the list of successful connections.
- **`shutdown()`** — Disconnects all servers, destroys processes, clears the
  connection map.

### McpRunner (Entry Point)

Located at: `golem-core/src/main/kotlin/dev/golem/mcp/McpRunner.kt`

The `runMcpServer()` function is the entry point for MCP mode. It:

1. Sets up the working directory from config
2. Creates a `MemoryStore`, `GolemJobStore`, `CheckpointManager`, and `JvmIndexCoordinator`
3. Builds the MCP-specific tool registry via `ToolRegistryFactory.buildMcpToolRegistry()`
4. Creates the `GolemMcpServer` instance
5. Starts either stdio or SSE transport based on the `transport` parameter

For SSE transport, it additionally:
- Creates a Ktor `embeddedServer` with SSE support
- Mounts the MCP handler at the `/mcp` route
- Enforces API key authentication via query parameter, `Authorization` header, or `X-Api-Key` header
- Runs indefinitely until the server is stopped

---

## Limitations

### SSE Transport (Client Mode)

**SSE transport for the MCP client is not yet supported.** Attempting to connect
to an SSE MCP server from the client manager throws:

```
SSE transport is not yet supported for MCP client connections.
Use transport='stdio' for local MCP servers.
```

Only `stdio` transport is supported for outgoing client connections.

### SSE Transport (Server Mode)

The SSE server mode works but is a **Ktor HTTP server** (not the MCP SDK's
built-in SSE transport). The MCP SDK's native SSE support may differ in
behavior from the stdio transport. Test thoroughly before using in production.

### Multiple Sessions

The MCP server currently does not support multiple concurrent sessions over
stdio. Each stdio connection is a single session.

### Tool Argument Types

The `jsonElementToValue()` converter handles `string`, `integer`, `boolean`,
and `null` values. Nested objects and arrays are serialized as their string
representation. Complex nested schemas may lose type fidelity.

### Tool Filtering

The MCP server exposes **all** tools in the registry with no filtering mechanism.
There is no allowlist/blocklist for which tools are exposed via MCP.

### Process Cleanup

When `McpClientManager.shutdown()` is called, spawned subprocesses are
destroyed with `destroyForcibly()`, which sends `SIGKILL` on Unix. This may
leave orphaned processes in edge cases (e.g., JVM crash).

---

## Examples

### Example 1: Expose Golem Tools to Claude Desktop

```bash
# 1. Start Golem in MCP mode
golem --mcp
```

```json
// 2. Add to claude_desktop_config.json
{
  "mcpServers": {
    "golem": {
      "command": "/usr/local/bin/golem-cli",
      "args": ["--mcp"]
    }
  }
}
```

### Example 2: Connect to External Filesystem MCP Server

```json
// ~/.golem/mcp-servers.json
[
  {
    "name": "filesystem",
    "transport": "stdio",
    "command": "npx",
    "args": ["-y", "@modelcontextprotocol/server-filesystem", "/home/user"],
    "enabled": true
  }
]
```

Golem's agents can now call `mcp_filesystem_read_file`, `mcp_filesystem_write_file`,
etc. alongside built-in tools.

### Example 3: Build a Custom MCP Client Integration

```kotlin
// Inside Golem's startup sequence, external MCP servers are auto-connected
val mcpClientManager = McpClientManager(toolRegistry)
mcpClientManager.connectAllFromConfig()
// All tools from external servers are now available with mcp_ prefix
```

### Example 4: SSE Network MCP Server

```bash
# Start SSE server on port 8091 with API key
golem --mcp --mcp-transport sse --mcp-port 8091 --api-key my-key
```

Then connect from any MCP client:
```
http://192.168.1.100:8091/mcp?apiKey=my-key
```
