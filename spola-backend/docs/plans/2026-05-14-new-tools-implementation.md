# Implementation Plan: 4 New Tools

## Overview
This plan adds four tool areas without reworking Golem's core architecture:

- Browser automation via Chrome DevTools Protocol over Ktor WebSocket client
- Image generation via an extensible provider abstraction
- Sandboxed code execution as a separate, safety-defaulted tool distinct from `shell`
- Clarify / ask-user flow via a SQLite-backed pending-question store modeled after `GateDecisionStore`

The current codebase already provides the extension seams needed:

- Tool registration is centralized in `registerTools()` and `ToolRegistryFactory`
- Provider-style integrations already exist in TTS
- Exposed-backed stores already exist for process gates and sessions
- API and MCP routing are already split cleanly
- The ReAct loop in `GolemAgent` is the correct place to pause for clarification before the next LLM call

No existing product decisions are re-opened here. This is an implementation plan only.

## 1. Browser Automation

### Files
Files to create:

- `golem-core/src/main/kotlin/dev/golem/browser/BrowserAutomationConfig.kt`
  Purpose: browser-specific config model and defaults.
  Key classes: `BrowserAutomationConfig`
- `golem-core/src/main/kotlin/dev/golem/browser/CdpClient.kt`
  Purpose: low-level Chrome DevTools Protocol transport over Ktor WebSocket.
  Key classes: `CdpClient`, `CdpRequest`, `CdpResponse`
- `golem-core/src/main/kotlin/dev/golem/browser/BrowserSession.kt`
  Purpose: manages Chrome target/session lifecycle and command helpers.
  Key classes: `BrowserSession`
- `golem-core/src/main/kotlin/dev/golem/browser/BrowserDomService.kt`
  Purpose: DOM querying, click/type helpers, snapshot extraction.
  Key classes: `BrowserDomService`, `BrowserNodeRef`
- `golem-core/src/main/kotlin/dev/golem/tools/BrowserTools.kt`
  Purpose: registers the 8 browser tools and translates tool args into browser actions.
  Key functions: `registerBrowserTools`

Files to modify:

- `golem-core/build.gradle.kts`
  Add Ktor client artifacts required for WebSocket client support.
- `gradle/libs.versions.toml`
  Add version catalog entries for `ktor-client-core`, `ktor-client-cio`, `ktor-client-websockets`.
- `golem-core/src/main/kotlin/dev/golem/GolemConfig.kt`
  Add browser config fields.
- `golem-core/src/main/kotlin/dev/golem/config/ConfigLoader.kt`
  Add `browser` section parsing and merge behavior.
- `golem-core/src/main/kotlin/dev/golem/tools/Tools.kt`
  Register browser tools in default/API/agent tool sets.
- `golem-core/src/main/kotlin/dev/golem/factory/ToolRegistryFactory.kt`
  Ensure browser tools appear in all intended registries, including MCP.
- `golem-core/src/main/kotlin/dev/golem/mcp/GolemMcpServer.kt`
  No structural change required if the tools are registered normally, but MCP exposure policy should be documented and tested here.
- `golem-core/src/main/kotlin/dev/golem/agent/PermissionEnforcer.kt`
  Extend runtime permission checks so browser automation is treated as network-capable.

### Configuration
Add a nested config section:

```yaml
browser:
  enabled: true
  host: "127.0.0.1"
  port: 9222
  launch-command: null
  launch-timeout-seconds: 15
  navigation-timeout-seconds: 30
  screenshot-dir: "~/.golem/browser"
  snapshot-max-length: 20000
```

`GolemConfig` additions:

- `browserEnabled: Boolean = true`
- `browserHost: String = "127.0.0.1"`
- `browserPort: Int = 9222`
- `browserLaunchCommand: String? = null`
- `browserLaunchTimeoutSeconds: Int = 15`
- `browserNavigationTimeoutSeconds: Int = 30`
- `browserScreenshotDir: String = Path.of(System.getProperty("user.home"), ".golem", "browser").toString()`
- `browserSnapshotMaxLength: Int = 20000`

Notes:

- `launchCommand` is optional and should only be used if the host wants Golem to start Chrome itself.
- If `enabled=false`, do not register the browser tools.

### Interfaces

```kotlin
package dev.golem.browser

data class BrowserAutomationConfig(
    val enabled: Boolean = true,
    val host: String = "127.0.0.1",
    val port: Int = 9222,
    val launchCommand: String? = null,
    val launchTimeoutSeconds: Int = 15,
    val navigationTimeoutSeconds: Int = 30,
    val screenshotDir: String,
    val snapshotMaxLength: Int = 20000,
)
```

```kotlin
package dev.golem.browser

interface BrowserSession : AutoCloseable {
    suspend fun navigate(url: String): String
    suspend fun click(selector: String): String
    suspend fun type(selector: String, text: String, clear: Boolean = true): String
    suspend fun snapshot(): String
    suspend fun screenshot(outputPath: String? = null): String
    suspend fun back(): String
    suspend fun scroll(x: Int = 0, y: Int = 600): String
    suspend fun currentUrl(): String
}
```

```kotlin
package dev.golem.tools

fun registerBrowserTools(
    registry: ToolRegistry,
    config: GolemConfig,
)
```

### Registration
Register in:

- `registerTools(registry, config, permissionEnforcer)`
- `ToolRegistryFactory.buildDefaultToolRegistry`
- `ToolRegistryFactory.buildAgentToolRegistry`
- `ToolRegistryFactory.buildApiToolRegistry`
- `ToolRegistryFactory.buildMcpToolRegistry`

Tool names:

- `browser_navigate`
- `browser_click`
- `browser_type`
- `browser_snapshot`
- `browser_screenshot`
- `browser_back`
- `browser_scroll`
- `browser_get_url`

Registration behavior:

- Only register when `config.browserEnabled` is true.
- Browser tools should be visible to normal agent, API, and MCP registries.
- Permission-scoped agents with `networkAccess=false` should not see them.

### MCP support
Expose via MCP:

- Yes, all 8 browser tools.

Rationale:

- These are regular agent tools, not interactive human-gate tools.
- They should behave the same in local CLI, API-backed agent runs, and MCP server mode.

### Security
Allowed:

- Connect only to configured local CDP host/port by default.
- Navigate web pages, inspect DOM, take screenshots.

Blocked / constrained:

- No arbitrary remote CDP endpoint discovery.
- No registration when browser tooling is disabled.
- Agent permission filtering should treat browser tools as network tools.
- Screenshot output paths must pass existing filesystem allowlist checks.
- If `launchCommand` is implemented, it must reuse shell safety checks or be off by default. The cleaner default is: no automatic launch unless explicitly configured.

Security note:

- Browser automation is effectively network-capable and can reach arbitrary websites through Chrome. It belongs in the same permission bucket as `web_search` and `web_fetch`, not as a purely local UI tool.

### Implementation Order
1. Add version catalog and `golem-core` dependencies for Ktor client WebSockets.
2. Extend `GolemConfig` and `ConfigLoader`.
3. Implement `BrowserAutomationConfig` and `CdpClient`.
4. Implement `BrowserSession` and DOM helpers.
5. Add `BrowserTools.kt` and register the 8 tools.
6. Extend `PermissionEnforcer` and agent registry filtering for browser/network coupling.
7. Add MCP exposure tests and API/agent integration tests.

### Tests
Unit tests:

- `CdpClient` request/response framing and message correlation by id.
- Browser tool argument validation.
- Snapshot truncation to `browserSnapshotMaxLength`.
- Screenshot path handling and default path generation.

Integration tests:

- Tool registration gated by `browserEnabled`.
- Permission filtering removes browser tools when `networkAccess=false`.
- MCP schema exposes all 8 tools when enabled.

Test strategy note:

- Avoid requiring real Chrome in standard test runs.
- Use a fake WebSocket CDP server in tests to emulate `Page`, `Runtime`, `DOM`, and `Input` responses.
- Keep any real-browser smoke test optional and out of the default unit test suite.

### Dependencies
New dependencies:

- `io.ktor:ktor-client-core-jvm`
- `io.ktor:ktor-client-cio-jvm`
- `io.ktor:ktor-client-websockets-jvm`

These are aligned with the existing Ktor version already in the repo. No non-Ktor browser library should be added.

## 2. Image Generation

### Files
Files to create:

- `golem-core/src/main/kotlin/dev/golem/image/ImageGenProvider.kt`
  Purpose: provider abstraction for image generation.
  Key classes: `ImageGenProvider`, `ImageGenRequest`, `ImageGenResult`, `ImageGenException`
- `golem-core/src/main/kotlin/dev/golem/image/OpenAiImageGenProvider.kt`
  Purpose: built-in DALL-E 3 implementation over HTTP.
  Key classes: `OpenAiImageGenProvider`
- `golem-core/src/main/kotlin/dev/golem/image/StabilityImageGenProvider.kt`
  Purpose: built-in Stability AI implementation over HTTP.
  Key classes: `StabilityImageGenProvider`
- `golem-core/src/main/kotlin/dev/golem/image/ImageGenProviderFactory.kt`
  Purpose: resolve default provider from config and allow plugin-provided overrides.
  Key classes: `ImageGenProviderFactory`
- `golem-core/src/main/kotlin/dev/golem/tools/ImageGenTools.kt`
  Purpose: register `generate_image`.
  Key functions: `registerImageGenTool`

Files to modify:

- `golem-core/src/main/kotlin/dev/golem/GolemConfig.kt`
  Add image generation config keys.
- `golem-core/src/main/kotlin/dev/golem/config/ConfigLoader.kt`
  Add `image-gen` section parsing and merging.
- `golem-core/src/main/kotlin/dev/golem/tools/Tools.kt`
  Register image generation tool.
- `golem-core/src/main/kotlin/dev/golem/factory/ToolRegistryFactory.kt`
  Ensure the tool is included in all applicable registries.
- Plugin extension points if needed:
  `golem-core/src/main/kotlin/dev/golem/plugin/...`
  Only if the current plugin API needs an explicit provider registration hook.

### Configuration
Add a nested config section:

```yaml
image-gen:
  enabled: true
  default-provider: "openai"
  api-key: null
  default-size: "1024x1024"
  output-dir: "~/.golem/images"
  openai-base-url: "https://api.openai.com"
  stability-base-url: "https://api.stability.ai"
```

`GolemConfig` additions:

- `imageGenEnabled: Boolean = true`
- `imageGenDefaultProvider: String = "openai"`
- `imageGenApiKey: String? = null`
- `imageGenDefaultSize: String = "1024x1024"`
- `imageGenOutputDir: String = Path.of(System.getProperty("user.home"), ".golem", "images").toString()`
- `imageGenOpenAiBaseUrl: String = "https://api.openai.com"`
- `imageGenStabilityBaseUrl: String = "https://api.stability.ai"`

Recommended refinement:

- Prefer provider-specific keys if you want first-class multi-provider support:
  `imageGenOpenAiApiKey`, `imageGenStabilityApiKey`.
- If you keep the already-decided single `imageGen.apiKey`, document clearly that the selected default provider owns that key.

### Interfaces

```kotlin
package dev.golem.image

data class ImageGenRequest(
    val prompt: String,
    val size: String,
    val outputPath: String? = null,
)

data class ImageGenResult(
    val provider: String,
    val outputPath: String,
    val bytesWritten: Long,
)

interface ImageGenProvider {
    val name: String
    suspend fun generate(request: ImageGenRequest): ByteArray
}

class ImageGenException(message: String, cause: Throwable? = null) : Exception(message, cause)
```

```kotlin
package dev.golem.image

object ImageGenProviderFactory {
    fun create(config: GolemConfig): ImageGenProvider
}
```

```kotlin
package dev.golem.tools

fun registerImageGenTool(
    registry: ToolRegistry,
    config: GolemConfig = GolemConfig(),
)
```

### Registration
Register in:

- `registerTools(registry, config, permissionEnforcer)`
- `ToolRegistryFactory.buildDefaultToolRegistry`
- `ToolRegistryFactory.buildAgentToolRegistry`
- `ToolRegistryFactory.buildApiToolRegistry`
- `ToolRegistryFactory.buildMcpToolRegistry`

Tool name:

- `generate_image`

Registration behavior:

- Config-gated exactly like `tts_say`.
- If `imageGenEnabled=false`, do not register.
- Provider resolution should happen once at registration time unless runtime override support is added later.

### MCP support
Expose via MCP:

- Yes, `generate_image`.

Rationale:

- It is a normal remote side-effecting tool like TTS or delivery.
- It does not rely on local human input or CLI-only interaction.

### Security
Allowed:

- Outbound HTTPS requests to the configured provider endpoint.
- Saving generated image bytes to an output path.

Blocked / constrained:

- Output path must pass `resolvePath` + `checkAllowed`.
- Validate `size` against a small allowlist rather than passing arbitrary strings through.
- Enforce max prompt length to avoid runaway request bodies.
- Never echo API keys in tool results or exceptions.

Security note:

- This is a networked tool. Permission filtering for `networkAccess=false` should hide it from restricted agents.
- The current registry filtering only removes tool names matching `web_` or `http`; update filtering to account for provider-backed tools such as `generate_image`.

### Implementation Order
1. Add config fields and config file parsing.
2. Add `ImageGenProvider` abstraction and exception type.
3. Implement OpenAI provider.
4. Implement Stability provider.
5. Add provider factory and optional plugin-provider lookup hook.
6. Add `generate_image` tool and output-path handling.
7. Extend permission filtering for provider-backed network tools.
8. Add unit and API/MCP registration tests.

### Tests
Unit tests:

- Provider factory chooses default provider correctly.
- `generate_image` uses `defaultSize` when omitted.
- Unsupported provider fails clearly.
- Invalid size fails fast.
- Output path override writes to expected location.
- HTTP error mapping for OpenAI and Stability providers.

Integration tests:

- Tool registration respects `imageGenEnabled`.
- MCP schema exposes `generate_image`.
- Restricted agents with `networkAccess=false` do not receive the tool.

Implementation test approach:

- Follow the TTS provider pattern and inject fake HTTP clients or overridable base URLs for deterministic tests.

### Dependencies
New dependencies:

- None.

Implementation should use standard HTTP facilities already present in the project, or the existing Java/Ktor HTTP stack already on the classpath.

## 3. Sandboxed Code Execution

### Files
Files to create:

- `golem-core/src/main/kotlin/dev/golem/codeexec/CodeExecConfig.kt`
  Purpose: code execution defaults and policy model.
  Key classes: `CodeExecConfig`
- `golem-core/src/main/kotlin/dev/golem/codeexec/CodeExecSandbox.kt`
  Purpose: temp-dir lifecycle, interpreter command building, timeout and memory limit enforcement.
  Key classes: `CodeExecSandbox`, `CodeExecRequest`, `CodeExecResult`
- `golem-core/src/main/kotlin/dev/golem/tools/CodeExecTools.kt`
  Purpose: register `run_code`.
  Key functions: `registerCodeExecTool`

Files to modify:

- `golem-core/src/main/kotlin/dev/golem/GolemConfig.kt`
  Add code execution config fields.
- `golem-core/src/main/kotlin/dev/golem/config/ConfigLoader.kt`
  Add `code-exec` section parsing and merging.
- `golem-core/src/main/kotlin/dev/golem/tools/Tools.kt`
  Register `run_code`.
- `golem-core/src/main/kotlin/dev/golem/factory/ToolRegistryFactory.kt`
  Ensure the tool is included in default/API/agent/MCP registries.
- `golem-core/src/main/kotlin/dev/golem/agent/PermissionEnforcer.kt`
  Add explicit runtime permission handling for `run_code`.

Optional files to modify:

- `golem-core/src/main/kotlin/dev/golem/tools/ShellTool.kt`
  Reuse argument parsing or output truncation helpers if it reduces duplication cleanly. Do not make `run_code` depend on unrestricted `shell` semantics.

### Configuration
Add a nested config section:

```yaml
code-exec:
  enabled: true
  timeout-seconds: 30
  max-memory-mb: 100
  allowed-languages:
    - python3
    - node
    - kotlin
    - shell
  temp-root: null
  capture-max-output-bytes: 51200
```

`GolemConfig` additions:

- `codeExecEnabled: Boolean = true`
- `codeExecTimeoutSeconds: Int = 30`
- `codeExecMaxMemoryMb: Int = 100`
- `codeExecAllowedLanguages: List<String> = listOf("python3", "node", "kotlin", "shell")`
- `codeExecTempRoot: String? = null`
- `codeExecCaptureMaxOutputBytes: Int = 50 * 1024`

Important implementation note:

- `GolemConfigFile` currently uses simple scalar/list fields, not arbitrary nested maps in the main class. Add a dedicated `CodeExecConfigFile` data class as done for `email` and `tts`.

### Interfaces

```kotlin
package dev.golem.codeexec

data class CodeExecConfig(
    val enabled: Boolean = true,
    val timeoutSeconds: Int = 30,
    val maxMemoryMb: Int = 100,
    val allowedLanguages: List<String> = listOf("python3", "node", "kotlin", "shell"),
    val tempRoot: String? = null,
    val captureMaxOutputBytes: Int = 50 * 1024,
)
```

```kotlin
package dev.golem.codeexec

data class CodeExecRequest(
    val language: String,
    val code: String,
    val timeoutSeconds: Int,
)

data class CodeExecResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val workDir: String,
)

class CodeExecSandbox(
    private val config: CodeExecConfig,
) {
    suspend fun run(request: CodeExecRequest): CodeExecResult
}
```

```kotlin
package dev.golem.tools

fun registerCodeExecTool(
    registry: ToolRegistry,
    config: GolemConfig,
)
```

### Registration
Register in:

- `registerTools(registry, config, permissionEnforcer)`
- `ToolRegistryFactory.buildDefaultToolRegistry`
- `ToolRegistryFactory.buildAgentToolRegistry`
- `ToolRegistryFactory.buildApiToolRegistry`
- `ToolRegistryFactory.buildMcpToolRegistry`

Tool name:

- `run_code`

Registration behavior:

- Config-gated; do not register if `codeExecEnabled=false`.
- Distinct from `shell`; do not overload `shell` with a mode flag.

### MCP support
Expose via MCP:

- Yes, `run_code`.

Rationale:

- It is a normal programmable tool.
- The separate safety model is exactly why it should be exposed as its own tool instead of forcing MCP clients to use `shell`.

### Security
Allowed:

- Execute only configured languages.
- Run in an isolated temp directory per invocation.
- Enforce timeout and memory limit.

Blocked / constrained:

- No network.
- Read-only filesystem outside the temp directory.
- Temp directory must be deleted on completion.
- Reject languages not in `allowedLanguages`.
- Reject excessive timeout overrides; clamp to a safe max.

Realism note:

- `ulimit -v` and shell wrappers are not a strong sandbox.
- Read-only filesystem and network denial need OS-level enforcement to be real. Since the decision is to wrap existing shell behavior, document the boundary honestly:
  this is a safety-defaulted execution helper, not a hardened security sandbox.

Implementation approach:

- Preferred: invoke `/usr/bin/env bash -lc` with a generated wrapper that sets `ulimit -v`, `ulimit -t`, switches to temp dir, and runs the interpreter command.
- For `python3`, `node`, and `shell`, write code to a temp file and execute the interpreter directly against that file.
- For Kotlin, use `kotlin -e` only if the environment reliably supports it; otherwise writing a `.kts` temp file and invoking `kotlinc -script` is more robust. Since the decision already says `kotlin -e`, keep that, but call out compatibility testing as mandatory.

### Implementation Order
1. Add config fields and config file parsing.
2. Implement `CodeExecConfig` and request/result models.
3. Implement temp-dir lifecycle and interpreter mapping.
4. Implement timeout/output truncation/memory limit behavior.
5. Add `run_code` tool registration.
6. Extend permission filtering and runtime enforcement.
7. Add integration tests for cleanup and policy behavior.

### Tests
Unit tests:

- Reject unsupported languages.
- Use default timeout when omitted.
- Clamp timeout override to configured maximum.
- Output truncation matches shell-tool behavior.
- Temp directory is created and removed.

Integration tests:

- `python3`, `node`, `shell` happy path.
- Non-zero exit code surfaces stderr and exit code.
- Timeout produces deterministic failure.
- Large output is truncated.
- Registration respects `codeExecEnabled`.
- MCP schema exposes `run_code`.

Platform tests:

- Kotlin execution path must be tested explicitly on the supported host setup because `kotlin -e` behavior varies by installation.

### Dependencies
New dependencies:

- None.

Implementation should reuse JDK process execution and existing shell/file patterns.

## 4. Clarify / Ask User

### Files
Files to create:

- `golem-core/src/main/kotlin/dev/golem/clarify/ClarifyStore.kt`
  Purpose: SQLite-backed store for pending and answered clarification questions, patterned after `GateDecisionStore` but session-oriented.
  Key classes: `ClarifyStore`, `ClarifyRequest`, `ClarifyAnswer`, `PendingClarification`
- `golem-core/src/main/kotlin/dev/golem/tools/ClarifyTools.kt`
  Purpose: register `clarify`.
  Key functions: `registerClarifyTool`
- `golem-core/src/main/kotlin/dev/golem/api/routes/ClarifyRoutes.kt`
  Purpose: API endpoints for pending clarify questions and answer submission.
  Key functions: `apiClarifyRoutes`

Files to modify:

- `golem-core/src/main/kotlin/dev/golem/GolemConfig.kt`
  Add clarify DB path and polling config if needed.
- `golem-core/src/main/kotlin/dev/golem/config/ConfigLoader.kt`
  Add config parsing/merge.
- `golem-core/src/main/kotlin/dev/golem/GolemAgent.kt`
  Integrate session-aware clarify pause into the ReAct loop.
- `golem-core/src/main/kotlin/dev/golem/api/AgentRunHandler.kt`
  Ensure session IDs are propagated into agent runs.
- `golem-core/src/main/kotlin/dev/golem/api/StreamHandler.kt`
  Emit status updates when waiting for clarification.
- `golem-core/src/main/kotlin/dev/golem/Runner.kt`
  Update REPL/CLI flow to answer pending clarification inline through stdin.
- `golem-core/src/main/kotlin/dev/golem/api/ApiModels.kt`
  Add clarify request/response payload models.
- `golem-core/src/main/kotlin/dev/golem/api/GolemApiServer.kt`
  Instantiate `ClarifyStore` and register clarify routes.
- `golem-core/src/main/kotlin/dev/golem/tools/Tools.kt`
  Register the `clarify` tool.
- `golem-core/src/main/kotlin/dev/golem/factory/ToolRegistryFactory.kt`
  Register clarify tool in normal agent/API registries but not MCP.
- `golem-core/src/main/kotlin/dev/golem/mcp/GolemMcpServer.kt`
  No tool-schema change required if the tool is absent from the MCP registry, but this exclusion should be covered by tests.
- `golem-core/src/main/kotlin/dev/golem/process/GateDecisionStore.kt`
  Either extend directly or reuse patterns only.

Recommended structural choice:

- Do not overload `GateDecisionStore`'s `gates` table with clarification records.
- Reuse the Exposed/store pattern, but create a separate `clarifications` table because the lifecycle and cardinality differ:
  process gates are keyed by `runId + stepName`; clarification is keyed by `sessionId + questionId` and may need multiple sequential records per session.

### Configuration
Add a nested config section:

```yaml
clarify:
  enabled: true
  db-path: "./.golem/clarify.db"
  poll-interval-ms: 1000
  default-timeout-seconds: 3600
```

`GolemConfig` additions:

- `clarifyEnabled: Boolean = true`
- `clarifyDbPath: String = "./.golem/clarify.db"`
- `clarifyPollIntervalMs: Long = 1000`
- `clarifyDefaultTimeoutSeconds: Long = 3600`

Important integration note:

- The current API run flow does not pass a `sessionId` into `GolemAgent.run(...)`.
- Clarify cannot work reliably across API/stream/CLI without making session identity first-class for agent runs.

### Interfaces

```kotlin
package dev.golem.clarify

data class PendingClarification(
    val id: String,
    val sessionId: String,
    val question: String,
    val choicesJson: String? = null,
    val status: String,
    val createdAt: Long,
    val answeredAt: Long? = null,
)

data class ClarifyAnswer(
    val answer: String,
    val answeredAt: Long = System.currentTimeMillis(),
)

interface ClarifyStore {
    fun createPending(sessionId: String, question: String, choices: List<String> = emptyList()): PendingClarification
    fun getPending(sessionId: String): PendingClarification?
    fun answer(sessionId: String, clarificationId: String, answer: String)
    fun awaitAnswer(sessionId: String, clarificationId: String, timeoutSeconds: Long): ClarifyAnswer?
}
```

```kotlin
package dev.golem.tools

fun registerClarifyTool(
    registry: ToolRegistry,
    clarifyStore: dev.golem.clarify.ClarifyStore,
    sessionIdProvider: () -> String?,
)
```

Recommended API models:

```kotlin
@Serializable
data class ClarifyPendingResponse(
    val sessionId: String,
    val clarificationId: String,
    val question: String,
    val choices: List<String> = emptyList(),
)

@Serializable
data class ClarifyAnswerRequest(
    val clarificationId: String,
    val answer: String,
)
```

### Registration
Register in:

- `ToolRegistryFactory.buildDefaultToolRegistry`
- `ToolRegistryFactory.buildAgentToolRegistry`
- `ToolRegistryFactory.buildApiToolRegistry`

Do not register in:

- `ToolRegistryFactory.buildMcpToolRegistry`

Tool name:

- `clarify`

Registration behavior:

- Register only when `clarifyEnabled=true`.
- The tool implementation must know the current agent session id. That is the main wiring challenge.

Required agent-run wiring:

1. Ensure every agent run has a stable session id.
2. Pass that session id into `GolemAgent.run(...)`.
3. Make the `clarify` tool write a pending record tied to that session.
4. Pause the ReAct loop after tool execution and before the next LLM call until an answer arrives.
5. Inject the answer back into the conversation as a user/tool-visible continuation event.

Recommended conversation shape after answer:

- Tool returns something like `Clarification requested: <id>`
- When the answer arrives, append a `UserMessage` such as:
  `Clarification answer for "<question>": <answer>`

This keeps the LLM flow simple and avoids inventing a new hidden message type.

### MCP support
Expose via MCP:

- No.

Rationale:

- The tool requires an out-of-band human answer path tied to local/API session state.
- The user has already decided MCP clients should use separate endpoints instead.

### Security
Allowed:

- Persist pending clarification prompts and answers in SQLite.
- CLI can read from stdin and store an answer.
- API can fetch pending clarify questions and submit answers.

Blocked / constrained:

- Do not expose clarify over MCP.
- Only one active pending clarification per session unless explicit queuing is designed.
- Sanitize and size-limit question and answer text.
- API endpoints must require the same bearer auth as other session/process routes.

Behavioral safety note:

- The agent must not continue making LLM calls while a clarification is pending.
- Otherwise the tool becomes advisory instead of an actual gate, which defeats the feature.

### Implementation Order
1. Add config fields and config file parsing.
2. Introduce `ClarifyStore` and SQLite schema.
3. Make session identity first-class in `AgentRunRequest`, `AgentRunHandler`, and `GolemAgent`.
4. Register `clarify` in non-MCP registries.
5. Update `GolemAgent` loop to pause on pending clarification before the next LLM call.
6. Add CLI inline answer flow in `runRepl`.
7. Add API routes for pending and answer submission.
8. Add streaming status events for `waiting_for_clarification`.
9. Add exclusion tests proving MCP registry does not expose `clarify`.

### Tests
Unit tests:

- `ClarifyStore` create/get/answer/await behavior.
- Choices serialization/deserialization.
- Only one pending clarification per session if that invariant is chosen.

Agent-loop tests:

- Tool call to `clarify` creates a pending record.
- Agent pauses before the next LLM call until answer arrives.
- Answer resumes the same run and is added to conversation.
- Timeout path if no answer arrives within configured limit.

API tests:

- `GET /api/clarify/pending/{sessionId}` returns pending question.
- `POST /api/clarify/{sessionId}` records answer.
- Auth enforcement matches process/session routes.

CLI tests:

- REPL inline clarify prompt reads stdin and resumes run.

MCP tests:

- `clarify` is absent from the MCP registry and schema output.

### Dependencies
New dependencies:

- None.

Reuse Exposed + SQLite already in the project.

## Build & Test
Implementation sequence across all four tools:

1. Add new config fields and `ConfigLoader` support for `browser`, `image-gen`, `code-exec`, and `clarify`.
2. Add new dependency catalog entries only for Ktor client WebSockets used by browser automation.
3. Implement the three self-contained provider/runtime layers:
   browser CDP client, image providers, code-exec sandbox.
4. Implement `ClarifyStore` and session-id propagation through API/CLI/agent runs.
5. Register tools in `Tools.kt` and `ToolRegistryFactory.kt`.
6. Update permission filtering to handle browser/image/code-exec correctly.
7. Add MCP inclusion/exclusion tests.
8. Add API, agent-loop, and store-level tests.
9. Run:
   `./gradlew :golem-core:test`
10. Run targeted integration verification for browser and CLI clarify flows, since those rely on host capabilities.

Recommended initial verification commands:

```bash
./gradlew :golem-core:test
./gradlew :golem-cli:run --args="'use clarify to ask me one question before proceeding'"
./gradlew :golem-cli:run --args="'generate an image of a red cube'"
```

Browser smoke testing should be manual or optional CI-gated because it depends on host Chrome with remote debugging enabled.

## Risk Register
- Browser automation needs new Ktor client modules even though the design says "zero new JAR dependencies." This is still zero new third-party families, but not literally zero additional artifacts.
- `PermissionEnforcer` and registry filtering currently classify tools mostly by name heuristics. New networked tools will be under-protected unless that logic is extended deliberately.
- `run_code` is not a true sandbox with only `ulimit` and temp dirs. The docs and tool description must state that clearly.
- Kotlin execution via `kotlin -e` may be inconsistent across environments. This needs explicit host verification.
- Clarify depends on stable session identity, but current API agent runs do not propagate session ids into `GolemAgent.run(...)`. That is the main architectural delta in this plan.
- Extending `GateDecisionStore` directly for clarify would couple two different workflows too tightly. Reuse the pattern, not the table.
- MCP exclusion for `clarify` must be enforced at registry-construction time, not by best-effort checks inside the tool itself.
