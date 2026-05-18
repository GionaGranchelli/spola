# Phase 6 — SSE MCP Client Transport

**Goal:** Implement SSE (Server-Sent Events) transport for the MCP client in `McpClientManager.kt`.

## Background

The MCP SDK 0.11.1 already provides `SseClientTransport` (via `io.modelcontextprotocol.kotlin.sdk.client.SseClientTransport`). The implementation in `McpClientManager.kt` just needs to use it instead of throwing.

Ktor client (CIO) is already a dependency in `golem-core/build.gradle.kts`.

## Changes

### 1. `golem-core/.../mcp/McpClientManager.kt`

**Import `SseClientTransport`:**
```kotlin
import io.modelcontextprotocol.kotlin.sdk.client.SseClientTransport
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlin.time.Duration.Companion.seconds
```

**Replace the throw in `connectToServer()` (line ~229):**
```kotlin
"sse" -> connectSse(config, client)
```

**New method `connectSse()`:**
```kotlin
private suspend fun connectSse(config: McpServerConfig, client: Client): McpConnection {
    val baseUrl = config.baseUrl
        ?: throw IllegalArgumentException("baseUrl is required for sse transport")
    val httpClient = HttpClient(CIO)
    val transport = SseClientTransport(
        client = httpClient,
        urlString = baseUrl,
        reconnectionTime = 30.seconds,
    )
    client.connect(transport)
    return McpConnection(
        config = config,
        client = client,
        process = null,
        httpClient = httpClient,
    )
}
```

### 2. `McpConnection` data class

Update `McpConnection` to accept an optional `httpClient: HttpClient?` field (default `null`), so existing stdio connections don't require changes.

```kotlin
data class McpConnection(
    val config: McpServerConfig,
    val client: Client,
    val process: Process? = null,
    val httpClient: HttpClient? = null,
)
```

### 3. `removeServer()` cleanup

In `removeServer()`, close the `httpClient` for SSE connections:
```kotlin
connection.client.close()
connection.process?.destroy()
connection.httpClient?.close()
scope.cancel()
```

### 4. Tests

Add a test in `McpClientManagerTest.kt`:
- SSE transport config validation (baseUrl required)
- SSE transport accepted (doesn't throw on connection attempt — may fail with connection refused but that's expected without a real server)
- Existing tests must still pass

## Constraints
- No new JAR dependencies needed (Ktor client already present, MCP SDK already present)
- Ktor: 3.3.3 (from version catalog)
- MCP SDK: 0.11.1 (from version catalog)
- Build must pass (333+ tests)
