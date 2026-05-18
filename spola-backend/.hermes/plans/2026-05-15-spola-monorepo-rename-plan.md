# Spola Monorepo & Rebranding Plan

> **Status:** Draft | **Target:** Merge Golem + OpenClaw into `spola/spola` monorepo, rename everything, launch spola.dev

**Goal:** Single cohesive monorepo for the Spola project (agent framework + mobile/desktop client + website), fully renamed from Golem/OpenClaw.

**Architecture:** Two existing Gradle multi-module projects merge into one. The backend agent framework (was Golem) becomes `spola-core` + `spola-cli`. The frontend client (was OpenClaw) becomes `spola-client` with its own submodules. The website lives in `docs/` and deploys to spola.dev.

**Tech Stack:** Kotlin 2.3.x (unified), Gradle 8.x, Compose Multiplatform, Ktor, SQLDelight, picocli, MkDocs or raw HTML/CSS for docs site.

---

## Phase 0: Domain & Infrastructure (1 session)

### Task 0.1: DNS + Email setup
- Point spola.dev to GitHub Pages (185.199.108.153, 185.199.109.153, 185.199.110.153, 185.199.111.153) or Netlify
- Set up `admin@spola.dev` email forwarding
- Add CNAME for `www.spola.dev`

### Task 0.2: GitHub org
- Create `github.com/spola` organization
- Transfer `golem` repo → `github.com/spola/spola` (rename after push)
- Transfer `openclaw-app` → merges into the same monorepo

### Task 0.3: Create repository
- Create empty `github.com/spola/spola` with MIT license + default branch `main`
- Set up branch protection on `main`

---

## Phase 1: Merge Repos (2-3 sessions)

### Task 1.1: Directory structure

```
spola/
├── spola-core/           # Was golem-core (core agent loop, tools, MCP, API, scheduler)
├── spola-cli/            # Was golem-cli (CLI entry point)
├── spola-client/         # Was openclaw-app root
│   ├── shared/           # KMP shared library
│   ├── composeApp/       # Compose Multiplatform UI
│   └── backend/          # App-specific Ktor server
├── docs/                 # Website source
│   ├── content/          # Markdown documentation
│   ├── assets/           # Images, fonts, logos
│   └── index.md          # Landing page
├── gradle/               # Shared Gradle config
│   ├── libs.versions.toml  # Unified version catalog
│   └── wrapper/          # Gradle wrapper
├── build.gradle.kts      # Root build with SonarQube + versioning
├── settings.gradle.kts   # All subprojects
├── gradle.properties
├── gradlew / gradlew.bat
├── AGENTS.md             # Project persona for AI agents
├── CONTRIBUTING.md
├── LICENSE               # MIT
└── README.md
```

### Task 1.2: Version catalog unification

Current versions to reconcile:

| Dep | Golem | OpenClaw | Target |
|-----|-------|----------|--------|
| Kotlin | 2.3.10 | 2.1.0 | 2.3.10 |
| Ktor | 3.3.3 | 3.0.0 | 3.3.3 |
| Compose | - | 1.8.0 | 1.8.0 (client only) |
| Coroutines | 1.10.2 | 1.x | 1.10.2 |
| AGP | - | 8.4.2 | 8.4.2 (client only) |

**Key decision:** The Golem backend has TramAI on a BOM (`libs.tramai.bom`). The OpenClaw client doesn't need TramAI. The version catalog must be shared but certain entries are module-specific.

### Task 1.3: gradle.properties reconciliation

Golem uses `kotlin.jvm.target=21`. OpenClaw uses `kotlin.mpp.stability.nowarn=true` and `android.useAndroidX=true`. Merge both.

### Task 1.4: settings.gradle.kts

```
rootProject.name = "spola"
include(":spola-core", ":spola-cli", ":spola-client:shared", ":spola-client:backend", ":spola-client:composeApp")
```

### Task 1.5: Git merge strategy

```bash
# Clone fresh spola repo
cd spola
git remote add golem ../golem
git remote add openclaw ../openclaw-app

# Fetch both
git fetch golem
git fetch openclaw

# Merge Golem into spola/ dirs
git merge golem/main --allow-unrelated-histories --no-commit
# Move golem-core/ → spola-core/, golem-cli/ → spola-cli/
git mv golem-core spola-core
git mv golem-cli spola-cli

# Merge OpenClaw into spola-client/
git merge openclaw/main --allow-unrelated-histories --no-commit
git mv . spola-client/  # careful with root files

# Commit the merge
git commit -m "feat: merge Golem + OpenClaw into Spola monorepo"
```

---

## Phase 2: Rename Backend (spola-core + spola-cli) (3-4 sessions)

### Task 2.1: Package rename `dev.spola` → `dev.spola`

All packages in `spola-core` and `spola-cli`:

```bash
# Replace package declarations in .kt files
# dev/golem/api → dev/spola/api
# dev/golem/memory → dev/spola/memory
# etc.
```

**Files to change:**
- ~15 directory moves: `golem-core/src/main/kotlin/dev/golem/` → `spola-core/...`
- Package declarations in ~200 .kt files
- All `import dev.spola.*` references

### Task 2.2: Rename Gradle modules

- `golem-core/build.gradle.kts` → `spola-core/build.gradle.kts`
- `golem-cli/build.gradle.kts` → `spola-cli/build.gradle.kts`
- Root `build.gradle.kts` → update `allprojects` references
- `settings.gradle.kts` → update `include` paths

### Task 2.3: Update config paths

- `~/.golem/config.yaml` → `~/.spola/config.yaml` (or keep `.golem` as legacy compat)
- All `"./golem/"` directory references in code
- Default DB paths: `.golem/memory.db` → `.spola/memory.db`

### Task 2.4: Rename CLI

- `./golem-cli/build/install/golem-cli/bin/golem-cli` → `spola`
- Update picocli `@Command(name = "golem")` → `"spola"`
- Update all `--help` and usage strings
- Update docs/README/spec references

### Task 2.5: MCP server name

- `golem_mcp` → `spola_mcp` in MCP transport configs
- JSON-RPC method namespaces

### Task 2.6: Update docs/ADRs/specs

- All files in `docs/adr/`, `docs/specs/`, `docs/`
- README.md, GETTING_STARTED.md, TESTING.md

---

## Phase 3: Rename Frontend (spola-client) (2-3 sessions)

### Task 3.1: Package rename `dev.spola.app` → `dev.spola.client`

Same as backend: directory moves + package declarations + imports.

### Task 3.2: App display name

- Android: `strings.xml` app_name → "Spola"
- Desktop: window title → "Spola"
- Application ID: `dev.spola.app` → `dev.spola.client`

### Task 3.3: SQLDelight schema

- `OpenClawDb.sq` → rename generated code references
- `OpenClawDbQueries` → `SpolaDbQueries`
- Migration path: the old DB files need schema migration or just start fresh

### Task 3.4: API endpoint references

- The OpenClaw client calls `GET /api/...` on the Golem server. If the Spola server routes change, update client URLs.
- Pairing flow: `X-Pairing-Token` header, `/api/pairing/info`

---

## Phase 4: Website & Documentation (2-3 sessions)

### Task 4.1: Choose site generator

Options:
| Tool | Pros | Cons |
|------|------|------|
| **MkDocs** + Material theme | Simple markdown, search, dark mode | Requires Python |
| **Docusaurus** | React, versioned docs | Node.js, heavier |
| **Raw HTML/CSS** | Zero deps, fast | Manual work |
| **GitHub Pages Jekyll** | Free, automatic | Ruby-based |

**Recommendation:** MkDocs with Material theme. You write markdown, it generates a beautiful site. Integrates with spola.dev via GitHub Pages.

### Task 4.2: Site structure

```
docs/content/
├── index.md               # Landing page — what is Spola
├── getting-started/
│   ├── install.md         # Install CLI / download app
│   ├── quickstart.md      # First agent run
│   └── pairing.md         # Phone pairing guide
├── guide/
│   ├── architecture.md    # How it works (TramAI + spool metaphor)
│   ├── cli.md             # CLI reference
│   ├── api.md             # REST API reference
│   ├── mcp.md             # MCP protocol
│   ├── scheduling.md      # Cron jobs
│   └── processes.md       # Deterministic process engine
├── examples/
│   └── ...
├── contributing.md
└── tramai/                # TramAI library docs
    ├── overview.md
    ├── providers.md
    ├── tools.md
    └── structured-output.md
```

### Task 4.3: Visual identity

- Logo: A stylized spool/thread mark (can commission or design simple SVG)
- Colors: Dark theme (the current Golem aesthetic)
- Favicon + OG meta tags

### Task 4.4: Deploy pipeline

- GitHub Actions: on push to `main`, rebuild site, deploy to GitHub Pages
- spola.dev CNAME → GitHub Pages (or Netlify for preview deployments)

---

## Phase 5: Docker + Release Pipeline (1-2 sessions)

### Task 5.1: Dockerfile rename

- `Dockerfile` update: package names, JAR references
- `docker-compose.yml` update: service names, image tags

### Task 5.2: Release workflow

GitHub Actions:
- `spola-core` + `spola-cli`: build → publish JAR to GitHub Releases
- `spola-client`: build APK → attach to GitHub Release
- Site deploy to spola.dev

### Task 5.3: Version alignment

- Single version in root `gradle.properties` (e.g., `0.2.0`)
- All modules use the same version

---

## Timeline Estimate

| Phase | Sessions | Can be done remotely? |
|-------|----------|-----------------------|
| 0. Domain + Infra | 1 | Yes (DNS + GitHub) |
| 1. Merge repos | 2-3 | Yes |
| 2. Rename backend | 3-4 | Yes (needs build + test) |
| 3. Rename frontend | 2-3 | Yes (needs APK build) |
| 4. Website + docs | 2-3 | Yes |
| 5. Docker + CI | 1-2 | Yes |

**Total: ~11-16 sessions across 1-2 weeks of focused work.**

---

## Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Kotlin version mismatch | Build failures | Pin to 2.3.10 across all modules |
| TramAI BOM conflicts | Dep resolution errors | Test with `./gradlew dependencies` before committing |
| Git history loss | Missing attribution | `git mv` keeps history; `git merge --allow-unrelated-histories` preserves both |
| OpenClaw `backend/` conflicts | Naming collision with Golem's "backend" | Rename to `spola-client/server/` or `spola-client/gateway/` |
| spola.dev email deliverability | Mail goes to spam | Set up SPF/DKIM/DMARC |

---

## Immediate Next Steps (today)

1. Create `github.com/spola` org
2. Register spola.dev DNS pointing to GitHub Pages
3. Start Phase 1 merge in a local branch
