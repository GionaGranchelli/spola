# AGENTS.md — OpenClaw App v0.0.3

## Purpose
OpenClaw KMP app — Kotlin Multiplatform Compose Desktop/Android client for session-based chat with Golem agent backend running via TramAI & Hermes. Features model selection, chat with Golem agents, Kanban task management, Scheduler, Memory inspection, Tools browser, workflow toggling, and Settings.

**Version:** 0.0.3 (versionCode=3)  
**Last scan:** 46 → 19 SonarQube issues (May 16 2026)

---

## Repo shape

```
openclaw-app/
├── shared/               # KMP library — DTOs, SQLDelight persistence, network client, state store
├── backend/              # Ktor HTTP server — session/chat/memory/file Kanban + Workflow APIs
├── composeApp/           # Compose Multiplatform UI (Desktop + Android)
│   └── src/
│       ├── commonMain/   # Shared UI: App.kt, pages/, components/, decompose/, theme/
│       ├── androidMain/  # MainActivity, PairingScanner, Platform (camera, storage)
│       └── desktopMain/  # Main.kt, PairingScanner, Platform
├── build.gradle.kts      # Root + SonarQube plugin config
├── settings.gradle.kts   # :shared, :backend, :composeApp
└── gradle/libs.versions.toml  # Version catalog
```

### Stack
| Layer | Tech | Version |
|-------|------|---------|
| Language | Kotlin | 2.1.0 |
| UI | Compose Multiplatform | 1.8.0 |
| Navigation | Decompose (component tree) | 3.2.2 |
| HTTP server | Ktor (Netty) | 3.0.0 |
| HTTP client | Ktor (OkHttp) | 3.0.0 |
| Persistence | SQLDelight | 2.0.2 |
| Serialization | kotlinx-serialization | 1.8.0 |
| Android SDK | 35 (min 24) | AGP 8.4.2 |
| Gradle | 8.11.1 | |

---

## Working rules
- **Backend-first design**, UI thin. Shared DTOs are the source of truth.
- Sessions are first-class citizens; each session may bind to a different model/provider.
- No new JAR dependencies unless absolutely necessary. Kotlin stdlib / Compose Material3 only.
- **Build verification:** `./gradlew :composeApp:compileKotlinDesktop`
- **APK build:** `./gradlew :composeApp:assembleDebug` → `composeApp/build/outputs/apk/debug/composeApp-debug.apk`
- **SonarQube:** `./gradlew --no-parallel sonar` (needs `SONAR_TOKEN` or `-Dsonar.token=<token>`)
- Bump `versionName`+`versionCode` in `composeApp/build.gradle.kts` for each new APK.
- Backend has pre-existing SQLDelight codegen issue (`OpenClawDb` generation) — not introduced by feature work.

---

## Navigation (6 tabs via bottom bar)
1. **Chat** — Session-based messaging with Golem agents
2. **Kanban** — 3-column task board (todo, in_progress, done), full CRUD via REST API
3. **Workflows** — List workflow definitions with enable/disable toggle
4. **Tools** — Browse available Golem tools (fetched from `/api/tools`)
5. **Memory** — View/save persistent memory entries
6. **Settings** — App configuration (model, provider, API keys, theme)

---

## Architecture

### composeApp UI (Decompose component tree)
```
RootComponent
├── SessionListComponent     (session list drawer/sidebar)
├── DefaultDashboardComponent (main content, hosts tab-selected pages)
└── 6 child pages: ChatPage, KanbanPage, WorkflowsPage, ToolsPage, MemoryPage, SettingsPage
```

### shared (KMP library)
- **Models.kt** — All API DTOs (session, message, file, model, kanban)
- **GolemModels.kt** — Agent-specific models (tools, memory, workflows)
- **GolemClient.kt** — Ktor HTTP client: all `/api/*` calls to Golem backend or Hermes gateway
- **AppStateStore.kt** — Persisted app configuration (selected model, provider, theme)
- **DriverFactory.kt** — `expect`/`actual` SQLDelight driver for each platform
- **OpenClawDb.sq** — 6 tables: ModelEntity, ChatSessionEntity, MessageEntity, AppStateEntity, AuditEventEntity, FileEntity

### backend (Ktor server)
#### Routes
| Route | Method | Purpose |
|-------|--------|---------|
| `/sessions` | GET | List all sessions |
| `/session/{id}` | GET | Get session |
| `/session/{id}` | DELETE | Delete session |
| `/session/{id}/messages` | GET | Get session messages |
| `/session/{id}/message` | POST | Add message |
| `/session/{id}/file` | GET | List files in session |
| `/session/{id}/file` | POST | Upload file |
| `/api/kanban` | GET | List kanban cards |
| `/api/kanban` | POST | Create kanban card |
| `/api/kanban/{id}` | PUT | Update kanban card |
| `/api/kanban/{id}` | DELETE | Delete kanban card |
| `/api/workflows` | GET | List workflow definitions |
| `/api/workflows/{id}/toggle` | POST | Toggle workflow enabled/disabled |

#### Managers
- **CommandManager.kt** — Shell command execution (scoped, permissioned)
- **FlowManager.kt** — Flow orchestration (session lifecycle, streaming)

#### Services
- ChatRoutingService, ChatProviders, BackendMetaService, ModelCatalogService, OpenClawGatewayChatProvider, SpeechService, TrustAuth

#### Repositories
- SessionRepository, MessageRepository, FileRepository, AuditRepository — SQLDelight-backed

---

## SonarQube Status (May 16 2026)

**Current:** 0 bugs, 2 vulnerabilities, 17 code smells — 19 total open issues

### Vulnerabilities (2 — CRITICAL)
1. `build.gradle.kts:87` — Android release obfuscation not enabled
2. `Platform.kt:16` — Android external storage access

### Critical Code Smells (11 — Cognitive Complexity)
| File | Complexity | Threshold |
|------|-----------|-----------|
| SettingsPage.kt:27 | 64 (worst) | 15 max |
| SessionRoutes.kt:17 | 61 | 15 max |
| FileRoutes.kt:30 | 45 | 15 max |
| WorkflowsPage.kt:47 | 44 | 15 max |
| ChatPage.kt:752 | 39 | 15 max |
| ChatPage.kt:62 | 36 | 15 max |
| ChatPage.kt:980 | 35 | 15 max |
| ChatPage.kt:908 | 30 | 15 max |
| OpenClawRestGatewayClient.kt:47 | 21 | 15 max |
| Platform.kt:80 | Empty function | - |
| Platform.kt:87 | Empty function | - |

### Major Code Smells (6)
- **Excessive parameters:** ChatPage.kt:267 (13 params), ChatPage.kt:313 (9 params), KanbanPage.kt:153 (9 params)
- **Hardcoded dispatchers:** SessionRoutes.kt:106, CommandManager.kt:73, CommandManager.kt:79

### Quality Metrics
| Metric | Value |
|--------|-------|
| Bugs | 0 ✅ |
| Vulnerabilities | 2 🔴 |
| Code smells | 17 🟡 |
| Coverage | 0% (no tests configured) |
| Duplication | 3.8% |
| NCLOC | 8,000 |
| Tech debt | ~7.5h |

---

## Tests
- **backend:** `BackendBootstrapTest.kt`, `ModelEndpointsIntegrationTest.kt` (Ktor test host)
- **shared:** empty test directories
- **composeApp:** no tests yet

---

## Golem Remote API Server
- **Host:** `100.76.218.127:8082` (Tailscale)
- **Mode:** `--api --api-host 0.0.0.0 --insecure --api-key golem-remote-mesh`
- **Health:** `GET /health` (returns 404 — use `GET /api/tools` to verify)
- **Endpoints:** `api/tools` (53 tools), `api/kanban`, `api/workflows`, `api/scheduler`, `api/memory`
- **Config:** `pairingToken` + `apiKey` both required in camelCase YAML

---

## Near-term priorities
1. **SonarQue cleanup** (19 issues — cognitive complexity refactors, hardcoded dispatchers)
2. **Kanban/Workflow persistence** (move from in-memory to SQLDelight)
3. **Android polish** (theme, gestures, dark mode)
4. **Context size overview** in ChatPage (planned Phase 5)
5. **Memory persistence end-to-end** test (planned Phase 6)
6. **Scheduler page** UI improvements (currently lists Golem scheduler jobs)

---

## Build commands
```bash
# Desktop compilation
./gradlew :composeApp:compileKotlinDesktop

# Backend fat JAR
./gradlew :backend:shadowJar

# Android APK (debug)
./gradlew :composeApp:assembleDebug

# SonarQube scan (after localhost:9000 is up)
SONAR_TOKEN=squ_e2c2eda2cdae628e8170bb0a22c3a6b57a3afd75 ./gradlew --no-parallel sonar

# Full build (all modules)
./gradlew build
```
