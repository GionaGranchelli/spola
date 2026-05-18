# Persona System

Golem's persona system lets you define **who the agent is** — its identity, role, behavior guidelines, and expertise area. Personas are files, not database records. The system uses a discovery chain, a filesystem-backed "Persona Pocket", and auto-injection into every session.

---

## 1. Overview

The persona is the **system prompt** — the foundational instruction that shapes the agent's behavior. Golem supports three layers of persona configuration:

| Layer | Source | When It's Used |
|-------|--------|----------------|
| 1 | `AGENTS.md` or `CLAUDE.md` in working directory | Default discovery |
| 2 | Explicit `--persona` flag or `persona` config key | Override |
| 3 | **Persona Pocket** (`~/.golem/people/*.md`) | Active persona activation |
| 4 | Built-in default persona | Fallback if nothing else found |

These layers **combine** — the Persona Pocket active persona is prepended to the base system prompt, creating a stack:

```
▶ Active Persona: security-engineer
  Role: Security Engineer
  Tags: security, code-review
  (prose from ~/.golem/people/security-engineer.md)

▷ Base persona from AGENTS.md or default
```

---

## 2. Persona Loading — PersonaLoader

`PersonaLoader` discovers and loads the base persona from a file.

### Discovery Priority

```kotlin
fun load(
    explicitPath: String? = null,   // from --persona flag
    workingDirectory: String = ".",  // project root
): String
```

1. **Explicit path** — if `--persona /path/to/file` is provided, load exactly that file
2. **AGENTS.md** — in the working directory
3. **CLAUDE.md** — in the working directory
4. **Default persona** — built-in fallback

```kotlin
// Explicit path wins
PersonaLoader.load(explicitPath = "/home/user/my-agent.md")

// Auto-discover: AGENTS.md > CLAUDE.md > default
PersonaLoader.load()

// With custom working directory
PersonaLoader.load(workingDirectory = "/home/user/project")
```

### Default Persona

If no file is found, the built-in default is used:

```text
You are Golem, a JVM-based autonomous coding agent.
You help users build, debug, and understand Java/Kotlin projects.
You have access to tools: read, write, search files, run shell commands,
and maintain memory across sessions...
```

---

## 3. Persona Pocket — `~/.golem/people/*.md`

The Persona Pocket is a directory of markdown files at `~/.golem/people/`. Each `.md` file is a reusable persona profile with **YAML frontmatter** and a **prose body**.

### Directory Structure

```
~/.golem/people/
├── security-engineer.md
├── python-expert.md
├── database-admin.md
└── my-custom-role.md
```

### Schema — YAML Frontmatter + Prose Body

Each file can have YAML frontmatter between `---` markers:

```markdown
---
name: security-engineer
role: Security Engineer
tags: security, code-review, owasp
sources: OWASP Top 10, CWE, SEI CERT
---

You are a senior application security engineer with 15 years of experience.
You specialize in:

- **Static analysis** — finding vulnerabilities in source code
- **Threat modeling** — STRIDE, attack trees
- **Secure architecture** — zero-trust, defense in depth

Always provide CWE references and CVSS scores with your findings.
```

### Frontmatter Fields

| Field | Type | Description |
|-------|------|-------------|
| `name` | String | Persona name (defaults to filename stem if absent) |
| `role` | String | Role title |
| `tags` | List or String | Tags for organization |
| `sources` | List or String | Reference sources |
| (body) | Markdown | Full persona prose (after frontmatter) |

### Files Without Frontmatter

If a `.md` file has no YAML frontmatter (no opening `---`), the entire content is treated as the body and the filename stem is used as the name.

```markdown
# Custom Developer Persona

This is a simple persona file without frontmatter.
The name is derived from the filename.
```

### What Happens on Sync

`PersonaStore.syncFromDirectory()` scans `~/.golem/people/` and for each `.md` file:

1. Reads the file content
2. If it starts with `---`, extracts YAML frontmatter between `---` markers, parses fields
3. The rest after `---` is the body content
4. Creates a `PersonaRecord` with: name, role, tags, sources, body, and a summary (first sentence, up to 150 chars)
5. Upserts into the SQLite `personas` table

---

## 4. SQLite Integration — PersonaStore

`PersonaStore` is a SQLite-backed repository that syncs from the filesystem and provides lookup.

### Table Schema

```
Table: personas
  name       VARCHAR(256)  PRIMARY KEY
  role       VARCHAR(512)  nullable
  tags       VARCHAR(512)  nullable
  sources    TEXT           nullable
  body       TEXT           nullable
  summary    VARCHAR(512)   first sentence, up to 150 chars
  created_at LONG          epoch millis
  updated_at LONG          epoch millis
```

### Store Operations

```kotlin
val store = PersonaStore("/home/user/.golem/persona.db")

// Sync from filesystem (scans ~/.golem/people/*.md)
store.syncFromDirectory("/home/user/.golem/people")

// Get a persona by name
val persona: PersonaRecord? = store.get("security-engineer")

// List all personas (most recent first)
val all: List<PersonaRecord> = store.list()

// Upsert a persona (create or update)
store.upsert(PersonaRecord(
    name = "security-engineer",
    role = "Security Engineer",
    tags = "security, code-review",
    body = "You are a security engineer..."
))

// Delete by name
store.delete("old-persona")
```

### PersonaRecord Data Class

```kotlin
data class PersonaRecord(
    val name: String,
    val role: String? = null,
    val tags: String? = null,
    val sources: String? = null,
    val body: String? = null,
    val summary: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
```

### Sync Algorithm

```kotlin
store.syncFromDirectory("~/.golem/people")
// 1. Lists all *.md files in the directory
// 2. For each file:
//    - Parse YAML frontmatter (between --- markers)
//    - Build PersonaRecord with name, role, tags, sources, body, summary
// 3. Upsert each record into SQLite
// 4. Files with malformed YAML are logged to stderr and skipped
```

---

## 5. Injection Format — How Personas Enter the System Prompt

When a persona is active, `PersonaStore.formatForInjection()` produces a compact, readable block:

```kotlin
fun formatForInjection(persona: PersonaRecord): String {
    val sb = StringBuilder()
    sb.appendLine("## Active Persona: ${persona.name}")
    if (!persona.role.isNullOrBlank()) {
        sb.appendLine("Role: ${persona.role}")
    }
    if (!persona.tags.isNullOrBlank()) {
        sb.appendLine("Tags: ${persona.tags}")
    }
    if (!persona.body.isNullOrBlank()) {
        sb.appendLine()
        sb.append(persona.body.take(500))   // capped at 500 chars
    }
    return sb.toString().trimEnd()
}
```

**Result (injected into system prompt):**

```
## Active Persona: security-engineer
Role: Security Engineer
Tags: security, code-review, owasp

You are a senior application security engineer with 15 years of experience.
You specialize in:
- Static analysis — finding vulnerabilities in source code
- Threat modeling — STRIDE, attack trees
- Secure architecture — zero-trust, defense in depth
```

The body is capped at **500 characters** to stay token-efficient.

---

## 6. Auto-Injection — How Persona + Memory Combine at Session Start

`AgentFactory.create()` orchestrates the full persona injection pipeline:

```kotlin
// Step 1: Load base persona (AGENTS.md > CLAUDE.md > default)
val basePersona = PersonaLoader.load(
    explicitPath = config.personaPath,
    workingDirectory = config.workingDirectory,
)

// Step 2: Resolve active persona from Persona Pocket
val activePersonaName = config.activePersonaName
val activePersonaBlock = if (activePersonaName != null) {
    PersonaStore(config.personaDbPath).use { store ->
        store.syncFromDirectory(config.peopleDir)       // sync from disk
        val record = store.get(activePersonaName)        // lookup by name
        if (record != null) {
            PersonaStore.formatForInjection(record)       // format for prompt
        } else null
    }
} else null

// Step 3: Prepend active persona to base persona
val personaPrefix = if (activePersonaBlock != null) "$activePersonaBlock\n\n" else ""
var persona = "$personaPrefix$basePersona"

// Step 4: Append auto-loaded memories
val memories = memoryStore.listAll()
if (memories.isNotEmpty()) {
    val memoryBlock = memories.joinToString("\n") { "- **${it.key}**: ${it.value}" }
    persona = "$persona\n\n## Context from Memory (auto-loaded)\n$memoryBlock\n..."
}
```

### Final System Prompt Structure

```
## Active Persona: security-engineer          ← Optional, from Persona Pocket
Role: Security Engineer
Tags: security, code-review

You are a senior security engineer...

[Base persona from AGENTS.md or default]     ← Always present

## Context from Memory (auto-loaded)          ← Optional, if memories exist
- **user_prefers_tabs**: User prefers tabs...
```

For custom agents (`AgentFactory.createFromAgentDefinition()`), the agent's `systemPrompt` replaces the base persona:

```
## Active Persona: security-engineer          ← Optional, from Persona Pocket
Role: Security Engineer

[AgentDefinition.systemPrompt]               ← Custom agent's system prompt
```

---

## 7. Activation Paths

A persona can be activated three ways.

### CLI — `--persona-name` flag

```bash
# Activate a persona from Persona Pocket
golem --persona-name security-engineer

# Activate a persona with a custom AGENTS.md
golem --persona-name security-engineer --persona ./my-agents.md
```

### Config File — `persona-name` in config.yaml

```yaml
# ~/.golem/config.yaml
persona-name: security-engineer
persona: ./AGENTS.md              # base persona file override
```

### API — `personaName` in request body

When running via the API, the active persona name is part of the `GolemConfig` and can be set per-request.

### Configuration Fields

All three paths map to `GolemConfig` fields:

| Config Field | Type | Source | Default |
|-------------|------|--------|---------|
| `personaPath` | String? | From `--persona` flag or config `persona` | null (auto-discover) |
| `activePersonaName` | String? | From `--persona-name` flag or config `persona-name` | null (no pocket persona) |
| `personaDbPath` | String | Config default | `~/.golem/persona.db` |
| `peopleDir` | String | Config default | `~/.golem/people` |

---

## 8. CLI Commands

```
golem persona list          # List all personas (syncs from filesystem first)
golem persona show <name>   # Show full persona details
golem persona sync           # Sync personas from people directory
```

### persona list

```bash
$ golem persona list
Personas:
------------------------------------------------------------
  security-engineer (active)
    Role: Security Engineer
    Summary: You are a senior application security engineer...

  python-expert
    Role: Python Developer
    Summary: You are a senior Python developer specializing...

  database-admin
    Role: Database Administrator
    Summary: You manage PostgreSQL and MongoDB...

3 persona(s)
```

The active persona is marked with `(active)` based on `config.activePersonaName`.

### persona show

```bash
$ golem persona show security-engineer
Name: security-engineer
Role: Security Engineer
Tags: security, code-review, owasp
Sources: OWASP Top 10, CWE, SEI CERT
Summary: You are a senior application security engineer...

--- Body ---
You are a senior application security engineer with 15 years of experience.
You specialize in:
...
```

### persona sync

```bash
$ golem persona sync
Syncing from: /home/user/.golem/people
Sync complete.
Total personas: 3
```

---

## 9. Complete Example: Custom Security Engineer Setup

### Step 1: Create the persona file

```markdown
# ~/.golem/people/security-engineer.md
---
name: security-engineer
role: Security Engineer
tags: security, code-review, owasp
sources: OWASP Top 10, CWE, SEI CERT CERT Secure Coding
---

You are a senior application security engineer with deep expertise in:

- **Code review** — identify OWASP Top 10 vulnerabilities in Java, Kotlin, Python
- **Threat modeling** — STRIDE, attack trees, data flow diagrams
- **Hardcoded secrets** — API keys, tokens, credentials in source code
- **Authentication** — OAuth2, JWT, session management patterns

For each finding, provide:
1. CWE reference and CVSS score
2. The vulnerable code snippet
3. A fixed version of the code
4. Prevention recommendations

Be thorough but practical. Not every theoretical vulnerability needs fixing.
```

### Step 2: Activate it

```bash
# Via CLI
golem --persona-name security-engineer "Review src/auth/ for vulnerabilities"

# Via config
echo 'persona-name: security-engineer' >> ~/.golem/config.yaml
golem "Review src/auth/ for vulnerabilities"
```

### Step 3: What the agent sees

```
## Active Persona: security-engineer
Role: Security Engineer
Tags: security, code-review, owasp

You are a senior application security engineer with deep expertise in...

You are Golem, a JVM-based autonomous coding agent...
[default persona or AGENTS.md content]

## Context from Memory (auto-loaded)
- **project_auth_pattern**: This project uses OAuth2 with JWT...
```

### Step 4: Run a custom agent with this persona

```bash
golem agent run security-reviewer \
  "Audit src/auth/LoginController.kt for vulnerabilities"
```

The custom agent's own `systemPrompt` is used as the base, with the active persona from the Pocket prepended on top.

---

## 10. Error Handling

| Scenario | Behavior |
|----------|----------|
| Active persona name specified but file doesn't exist | Warning to stderr, continues without persona injection |
| Malformed YAML frontmatter | Warning to stderr, file skipped during sync |
| `peopleDir` doesn't exist | syncFromDirectory returns silently (no-op) |
| `personaDbPath` directory doesn't exist | Auto-created on PersonaStore init |
| Body over 500 chars | Truncated to 500 chars in injection format |
| No frontmatter, no body | Record created with empty body, filename as name |

---

## 11. PersonaStore Summary Utility

`PersonaStore.truncateToSummary()` extracts the first sentence from body text for the summary field:

```kotlin
fun truncateToSummary(text: String?, maxChars: Int = 150): String {
    if (text.isNullOrBlank()) return ""
    val cleaned = text.trim()
    val sentenceEnd = listOf(". ", ".\n", "!", "?", "\n\n")
        .mapNotNull { cleaned.indexOf(it).takeIf { idx -> idx >= 0 } }
        .minOrNull()
    return if (sentenceEnd != null && sentenceEnd < maxChars) {
        cleaned.substring(0, sentenceEnd + 1)
    } else {
        cleaned.take(maxChars)
    }.trim()
}
```

Used in `syncFromDirectory()` to auto-generate a short description for the list view.
