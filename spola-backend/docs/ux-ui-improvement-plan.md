# Golem UX/UI Improvement Plan

*Research compiled from OpenHuman, Hermes Agent, OpenClaw, and market UX analysis*

---

## 1. Current State Assessment

### What's Broken

| Issue | Severity | Location |
|-------|----------|----------|
| Everything on one dashboard | 🔴 | `App.kt` — 905 LOC monolith |
| No navigation/routing | 🔴 | Only 2 screens: Pairing + Dashboard |
| No custom theme (default Material3) | 🟡 | No design tokens |
| No accessibility (labels, semantics, focus) | 🔴 | WCAG AA gap |
| No loading/empty/error states | 🟡 | White screen on failure |
| Chat not Slack-like | 🔴 | No bubbles, no threading, no reactions |
| Session list is basic text | 🟡 | No search, no filter, no bulk actions |
| Tool/memory/scheduler on main view | 🔴 | Should be separate pages |
| No keyboard navigation | 🔴 | No shortcuts, no command palette |
| Configuration scattered | 🔴 | No dedicated settings page |

### What Works

- Pairing flow (QR code + URL) is solid
- Decompose navigation architecture is extensible
- GolemClient API layer is clean
- Backend API server is feature-rich (tools, memory, jobs, checkpoint, kanban, metrics)

---

## 2. Design Philosophy

**Principle:** *Progressive disclosure from chat to power tools.*

A user should see a clean chat interface first. Configuration, tools, memory, kanban, and scheduling are **one click away**, not cluttering the main view.

### Mental Model

```
┌────────────────────────────────────────────┐
│  🤖 Golem Agent                             │
├────────────────────────────────────────────┤
│  [💬 Chat]  [⚙️ Settings]  [🧰 Tools]       │  ← Bottom tab bar
│  [📋 Kanban] [⏰ Scheduler] [🧠 Memory]      │     (OpenHuman-style)
│  [📊 Metrics]                                │
├────────────────────────────────────────────┤
│                                            │
│         ┌──────────────────────┐           │
│         │                      │           │
│         │   Content Area       │           │
│         │   (changes per tab)  │           │
│         │                      │           │
│         └──────────────────────┘           │
│                                            │
└────────────────────────────────────────────┘
```

---

## 3. Features & Pages Map

### Page 1: 💬 Chat (Primary — default view)
| Feature | UX Pattern | Source |
|---------|-----------|--------|
| Message bubbles (Slack-like) | User: right-aligned blue, Agent: left-aligned white | Slack, OpenHuman |
| Threaded conversations | Branch from any message | Slack |
| Streaming with thinking indicator | Collapsible "Thinking..." <details> + tool timeline | OpenHuman, Claude |
| Tool call timeline | Real-time timeline: calling X → completed/failed | OpenHuman |
| Reactions (emoji picker) | On hover, latest message | OpenHuman |
| Copy button per message | Hover-reveal icon | OpenHuman |
| Timestamps | Compact, latest message only | OpenHuman |
| Input: expandable textarea | Shift+Enter newline, Enter send | Slack |
| Voice mode toggle | Mic button → whisper STT | OpenHuman |
| Model selector per session | Compact dropdown in header | Cursor |
| Autonomy slider | Tab → Ctrl+K → full agent | Karpathy/Cursor |
| Command palette (Ctrl+K) | Search actions, tools, sessions | Linear |

### Page 2: 🧰 Tools & MCP
| Feature | UX Pattern | Source |
|---------|-----------|--------|
| Tool list with search | Searchable grid/table | Hermes, OpenHuman Skills |
| Tool status indicators | Connected/Disconnected/Error | OpenHuman |
| MCP server config UI | Add/edit/remove MCP endpoints | Hermes |
| Tool execution history | Timeline of past tool calls | Claude Code |
| One-click enable/disable | Toggle switch per tool | OpenHuman |

### Page 3: 🧠 Memory
| Feature | UX Pattern | Source |
|---------|-----------|--------|
| Memory tree explorer | Hierarchical view | OpenHuman Intelligence |
| Search memory by keyword | Search bar with results | Hermes |
| Add/edit/delete memory | Inline editing | Linear |
| Memory categories | Tags/folders | OpenHuman |
| Memory stats | Total entries, last updated | Hermes |

### Page 4: 📋 Kanban Board
| Feature | UX Pattern | Source |
|---------|-----------|--------|
| Column view (Todo/Doing/Done) | Drag & drop cards | Linear |
| Create/edit/delete cards | Inline editing | Linear |
| Assign cards to sessions | Session selector | Linear |
| Card details panel | Right slide-out | Linear |
| Swimlanes by model/session | Grouping | Jira |

### Page 5: ⏰ Scheduler
| Feature | UX Pattern | Source |
|---------|-----------|--------|
| Job list with status | Table: name, schedule, next run, enabled | Hermes |
| Create job wizard | Natural language → cron | Notion |
| Job history | Last run, duration, success/fail | Hermes |
| Pause/resume/delete | Icon buttons | Linear |
| Calendar view of scheduled runs | Mini calendar | Google Calendar |

### Page 6: 📊 Metrics & Monitoring
| Feature | UX Pattern | Source |
|---------|-----------|--------|
| Agent run stats (total, success, fail) | Cards with sparklines | Vercel |
| Token usage over time | Line chart | OpenAI usage |
| Tool call frequency | Bar chart | Custom |
| Cost per model/provider | Table with totals | OpenRouter |
| Health status | Green/yellow/red dots | Vercel |
| Log viewer | Real-time log tailing | Vercel |

### Page 7: ⚙️ Settings
| Feature | UX Pattern | Source |
|---------|-----------|--------|
| API Key management | Masked input + test button | OpenHuman |
| Model/provider config | Dropdown selectors | Hermes |
| Persona editor | Text area with preview | Claude Code |
| Theme picker (dark/light) | Toggle | All |
| Plugin management | List with enable/disable | Hermes |
| Delivery config (Telegram, Email) | Form | Hermes |
| Voice/TTS settings | Provider + voice selector | OpenHuman |

### Page 8: Pairing/Setup
| Feature | UX Pattern | Source |
|---------|-----------|--------|
| QR code scanner (mobile) | Camera intent | OpenHuman |
| URL auto-connect | URL input + fetch | Current (works) |
| JSON paste | Text area | Current (works) |
| Trust status | Connected dot + server info | Current |
| Multiple host management | Host list with switch | Current |

---

## 4. Architecture Refactor

### Frontend (openclaw-app/composeApp)

**Current:** 7 files, all logic in `App.kt` + `RootComponent.kt`, 2 screens

**Target: Modular structure**

```
composeApp/src/commonMain/kotlin/dev/golem/app/
├── app/
│   ├── App.kt                          # Root: BottomTabBar + Page host
│   ├── theme/
│   │   ├── Theme.kt                    # GolemTheme (dark/light)
│   │   ├── Color.kt                    # Design tokens
│   │   ├── Typography.kt               # Font scale
│   │   └── Shapes.kt                   # Border radii
│   ├── components/
│   │   ├── MessageBubble.kt            # Chat bubble component
│   │   ├── ToolTimeline.kt             # Real-time tool call timeline
│   │   ├── CommandPalette.kt           # Ctrl+K palette
│   │   ├── ModelSelector.kt            # Compact model dropdown
│   │   ├── StatusIndicator.kt          # Green/yellow/red dot
│   │   └── EmptyState.kt              # Generic empty state
│   ├── navigation/
│   │   ├── BottomTabBar.kt             # Floating pill tab bar
│   │   └── Tab.kt                      # Tab definition enum
│   └── pages/
│       ├── ChatPage.kt                 # Chat UI (session list + messages)
│       ├── ToolsPage.kt                # Tool/MCP management
│       ├── MemoryPage.kt               # Memory explorer
│       ├── KanbanPage.kt               # Kanban board
│       ├── SchedulerPage.kt            # Job scheduler
│       ├── MetricsPage.kt              # Dashboard metrics
│       ├── SettingsPage.kt             # Configuration
│       └── PairingPage.kt              # Setup/Pairing
└── decompose/
    ├── RootComponent.kt                # Tab routing
    ├── ChatComponent.kt                # Chat state + stream
    ├── ToolsComponent.kt               # Tool list state
    ├── MemoryComponent.kt              # Memory state
    ├── KanbanComponent.kt              # Kanban state
    ├── SchedulerComponent.kt           # Scheduler state
    ├── MetricsComponent.kt             # Metrics state
    └── SettingsComponent.kt            # Settings state
```

### Backend Changes (golem-core)

| Change | Reason | Effort |
|--------|--------|--------|
| Add GET /api/tools/:name (tool detail) | Frontend tool page needs detail | Small |
| Add PUT /api/tools/:name (enable/disable) | Toggle tools | Small |
| Add GET /api/metrics/history | Time-series metric data | Medium |
| Add GET /api/memory/tree | Memory hierarchy | Small |
| Add GET /api/kanban/board | Full kanban state | Already exists? |
| Add SSE for metrics events | Real-time updates | Medium |
| Session CRUD persistence (SQLite) | Sessions survive restart | Medium |

---

## 5. UI/UX Detail: Chat Page (the priority)

### Layout
```
┌────────────────────────────────────────────────────────┐
│  ← Sessions  │  Model: gpt-4o  │  $0.02  │ ⏱ 12s  │  ← Header bar
├──────┬───────┴─────────────────────────────────────────┤
│      │                                                   │
│ Sess │  ┌─────────────────────────────────────┐         │
│ ions │  │  Giona                       10:30  │         │
│ list │  │  ┌─────────────────────────────┐    │         │
│      │  │  │ Write a Python script that  │    │         │
│      │  │  │ reads a CSV...              │    │         │
│      │  │  └─────────────────────────────┘    │         │
│  🔍  │  │                                     │         │
│      │  │  Golem Agent                10:31  │         │
│ Chat │  │  ┌─────────────────────────────┐    │         │
│ 1    │  │  │ Here's a Python script that  │    │         │
│ 2    │  │  │ reads a CSV...              │    │         │
│ 3    │  │  │                             │    │         │
│ 4    │  │  │ [📎 script.py] [📋 Copy]   │    │         │
│      │  │  └─────────────────────────────┘    │         │
│      │  │                                     │         │
│      │  │ 🤔 Thinking...                      │         │
│      │  │  📞 read_file ✓  write_file ✓       │         │
│      │  │                                     │         │
│      │  ├─────────────────────────────────────┤         │
│      │  │ 💬 Type a message...       🎤 ▶️    │         │
│      │  └─────────────────────────────────────┘         │
└──────┴──────────────────────────────────────────────────┘
```

### Key Interactions

| Interaction | Behavior |
|-------------|----------|
| Send message | Enter → message appears as user bubble → status: "Thinking..." → tool timeline appears → response streams in |
| Thinking collapse | Click "Thinking..." → shows tool call details (name, args, result) |
| Hover message | Shows timestamp + copy button + reaction picker |
| React to message | Click emoji icon → picker appears → emoji shown below message |
| Command palette | Ctrl+K → searchable overlay → actions: "New session", "Switch model", "Toggle dark mode" |
| Session search | Ctrl+F in session list → filter by name |
| Model switch | Click model badge → compact dropdown → session reconnects |
| Interrupt run | Stop button appears while agent is running → cancels current tool call |

### Accessibility (WCAG AA)

| Requirement | Implementation |
|-------------|---------------|
| Focus management | `Modifier.focusTarget()`, Tab order matches visual |
| ARIA semantics | `Modifier.semantics { role = "button"; contentDescription = "Send message" }` |
| Color contrast | 4.5:1 minimum (#737373 on white passes) |
| Reduced motion | `LocalLayoutDirection` check for animation disable |
| Screen reader | Streaming content announced via `contentDescription` updates |
| Keyboard navigation | Arrow keys in chat, Tab between sections, Enter to send |
| Focus indicator | Visible ring on focused elements (`Modifier.focusBorder()`) |
| Error announcements | `Modifier.semantics { liveRegion = LiveRegion.assertive }` |

---

## 6. Color Palette (Dark theme first)

```kotlin
object GolemColors {
    // Background
    val bg = Color(0xFF0D0D0D)          // Near-black
    val bgSurface = Color(0xFF1A1A1A)    // Card surface
    val bgElevated = Color(0xFF242424)   // Modal/sheet
    
    // Text
    val textPrimary = Color(0xFFE8E8E8)
    val textSecondary = Color(0xFFA0A0A0)
    val textMuted = Color(0xFF6B6B6B)
    
    // Accent
    val accent = Color(0xFF6C63FF)       // Primary purple-blue
    val accentLight = Color(0xFF8B83FF)  // Hover state
    
    // Semantic
    val success = Color(0xFF34C759)      // Green (Apple)
    val warning = Color(0xFFE8A728)      // Amber
    val error = Color(0xFFFF453A)        // Red (Apple)
    
    // Chat
    val userBubble = Color(0xFF6C63FF)   // Accent
    val userBubbleText = Color(0xFFFFFFFF)
    val assistantBubble = Color(0xFF2C2C2E)
    val assistantBubbleText = Color(0xFFE8E8E8)
    val codeBlock = Color(0xFF1E1E1E)
    
    // Tab bar
    val tabBg = Color(0xFF1A1A1A)        // Blur effect
    val tabInactive = textMuted
    val tabActive = accent
}
```

### Typography

```kotlin
object GolemTypography {
    val display = FontFamily.SansSerif    // Inter
    val mono = FontFamily.Monospace        // JetBrains Mono
    // Scale: 10, 12, 14, 16, 18, 24, 32
}
```

---

## 7. Implementation Roadmap

### Phase 1: Foundation (Week 1)
- [ ] Extract theme (Color, Typography, Shapes)
- [ ] Create `BottomTabBar` component
- [ ] Create page routing in `RootComponent`
- [ ] Move Pairing → standalone page
- [ ] Move Dashboard content → ChatPage
- [ ] Move Tools/Memory/Scheduler → separate pages

### Phase 2: Chat Redesign (Week 2)
- [ ] `MessageBubble` component (user/assistant variants)
- [ ] Streaming message composable
- [ ] Tool timeline composable
- [ ] Thinking indicator with collapse
- [ ] Session list with search
- [ ] Model selector dropdown
- [ ] Command palette (Ctrl+K)

### Phase 3: Pages (Week 3)
- [ ] Tools & MCP page
- [ ] Memory explorer page
- [ ] Kanban board page
- [ ] Scheduler page
- [ ] Metrics & monitoring page
- [ ] Settings page

### Phase 4: Polish (Week 4)
- [ ] Keyboard navigation audit
- [ ] Accessibility audit + fixes
- [ ] Empty states for all pages
- [ ] Loading skeletons
- [ ] Error boundaries
- [ ] Transitions/animations
- [ ] Light theme variant

### Phase 5: Backend (Parallel)
- [ ] Tool detail/enable/disable endpoints
- [ ] Memory tree endpoint
- [ ] Metrics history endpoint
- [ ] Session persistence (SQLite)
- [ ] SSE for real-time metrics

---

## 8. Key Takeaways from Research

**From OpenHuman:**
- Bottom tab navigation works better than sidebar for desktop AI apps
- Floating pill tabs auto-hide → full-screen immersive experience
- Card-based layout with soft shadows feels modern
- Typewriter greeting = personality from first load
- Tool timeline is the killer UX feature — users NEED to see what the agent is doing

**From Hermes/Community:**
- "Everything on one page" is the #1 complaint — fix first
- Keyboard shortcuts are non-negotiable for devs
- Slash commands (/compact, /help, /new) are power-user essentials
- Cost transparency builds trust (show token/API usage)

**From Market Research:**
- Autonomy slider (Tab → Ctrl+K → full agent) is the standout pattern
- Offline support is a key differentiator vs web-only apps
- Accessibility is table stakes, not nice-to-have
- Progressive disclosure wins over feature density
