# Golem Desktop App — Testing Guide

## Quick Start

```bash
# 1. Start the Golem backend
cd ~/Development/golem
export OPENAI_API_KEY="sk-..."
./gradlew :golem-cli:run --args="--api"

# 2. Start the desktop frontend (separate terminal)
cd ~/Development/openclaw-app
./gradlew composeApp:run
```

## Architecture

```
┌──────────────────────┐     Pairing      ┌──────────────────────┐
│  Golem Desktop App   │ ◄──────────────► │  Golem Backend       │
│  (Compose Desktop)   │    HTTP/SSE      │  (Ktor :8082)        │
├──────────────────────┤                  ├──────────────────────┤
│  6 Tabs:             │                  │  - Agent runs         │
│  💬 Chat             │                  │  - Tool registry      │
│  🧰 Tools            │                  │  - Memory (SQLite)    │
│  🧠 Memory           │                  │  - Scheduler          │
│  📋 Kanban           │                  │  - Kanban             │
│  ⏰ Scheduler        │                  │  - Checkpoints        │
│  ⚙️ Settings         │                  │  - Metrics            │
└──────────────────────┘                  └──────────────────────┘
```

## UI Test Cases

### 1. Pairing Flow
- [ ] App shows "Connect to Golem" screen when not paired
- [ ] Auto-Connect with server URL works
- [ ] Manual JSON paste works
- [ ] Error shown on invalid JSON
- [ ] Error shown on unreachable server
- [ ] Success → navigates to Dashboard with tab bar

### 2. Navigation
- [ ] Bottom tab bar shows 6 tabs: Chat, Tools, Memory, Kanban, Scheduler, Settings
- [ ] Tapping a tab switches the content area
- [ ] Active tab is highlighted with accent color (#6C63FF)
- [ ] Page transition animation is smooth

### 3. Chat Page
- [ ] Session sidebar is visible on the left
- [ ] Session list loads from backend
- [ ] Clicking a session selects it
- [ ] "+ New" button creates a new session
- [ ] "×" button shows delete confirmation
- [ ] Model selector shows available models
- [ ] Changing model updates session
- [ ] Input field accepts text
- [ ] Enter sends message to agent
- [ ] Message appears as user bubble (right-aligned, accent bg)
- [ ] Agent response appears as assistant bubble (left-aligned, dark bg)
- [ ] Streaming: text appears incrementally during generation
- [ ] Thinking indicator shown while agent is processing
- [ ] Copy button appears on hover over messages
- [ ] Sidebar can be collapsed/expanded

### 4. Tools Page
- [ ] Shows list of all registered tools
- [ ] Each tool shows name, description, parameters
- [ ] Tool toggle button works (calls POST /api/tools/{name}/toggle)
- [ ] Empty state shown when not connected

### 5. Memory Page
- [ ] Search bar is functional
- [ ] Results display matches from backend
- [ ] Empty state shown when not connected

### 6. Kanban Page
- [ ] Placeholder shown with "Coming soon" message
- [ ] Empty state shown when not connected

### 7. Scheduler Page
- [ ] Shows list of scheduled jobs
- [ ] Empty state shown when not connected

### 8. Settings Page
- [ ] Connection info displayed (host, port, trust ID)
- [ ] Trusted hosts list shown
- [ ] Theme card shows dark theme notice
- [ ] "Revoke Pairing" button navigates to pairing screen

### 9. Accessibility
- [ ] Tab key navigates through all interactive elements
- [ ] Focus indicator visible on focused elements
- [ ] Screen reader announces button labels
- [ ] Page transitions use crossfade (not jarring)

### 10. Visual
- [ ] Dark theme is consistent across all pages
- [ ] Accent color (#6C63FF) used for active/primary elements
- [ ] Text legible on all backgrounds (contrast)
- [ ] Loading skeletons shown during data fetch
- [ ] Empty states shown when no data
- [ ] Error states handled gracefully
- [ ] Tab bar is floating pill-style at bottom

## Known Issues

- Backend `:backend` module may fail to build (JDK toolchain) — not related to frontend
- iOS targets disabled on Linux (normal for Compose Multiplatform)
- No light theme variant (planned for future)
- Kanban page is placeholder (planned for future)

## Build Verification

```bash
# Frontend only
cd ~/Development/openclaw-app && ./gradlew :composeApp:compileKotlinDesktop

# Backend only  
cd ~/Development/golem && ./gradlew :golem-core:build
```
