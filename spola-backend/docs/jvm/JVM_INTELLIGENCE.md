---
sidebar_position: 1
title: JVM Intelligence
description: Complete documentation for Golem's JVM project intelligence system — scanning, symbols, dependencies, change impact, failure analysis, and incremental indexing.
---

# JVM Intelligence

Golem's JVM intelligence subsystem transforms the agent into a project-aware developer
that understands your Gradle module layout, Kotlin/Java symbols, inter-module dependencies,
git changes, build output errors, and project conventions. It's the difference between an
agent blindly editing files and one that knows exactly which module to compile, which tests
to run, and what went wrong when it fails.

---

## 1. Overview

The JVM intelligence system lives in the `dev.spola.jvm` package and covers seven capability
areas, all accessible via LLM tools, CLI commands, and programmatic API:

| Area | Purpose |
|------|---------|
| **Project Scanning** | Parse `settings.gradle.kts` and `build.gradle.kts` to discover modules, source roots, plugins, and declared dependencies |
| **Symbol Extraction** | Regex-based scraping of Kotlin (`.kt`) and Java (`.java`) files for classes, functions, properties, enums, annotations, and extension members |
| **Dependency Analysis** | Resolve direct and transitive `project()` dependencies between modules via BFS, with optional live Gradle command execution |
| **Change Impact** | Parse `git diff HEAD` to map file changes to affected modules, changed symbols, and likely affected tests |
| **Failure Explanation** | Parse Gradle console output into structured failure reports with root-cause analysis and fix suggestions (10+ patterns) |
| **Incremental Indexing** | Java `WatchService`-based filesystem watcher with debounce, per-query freshness policies, and smart incremental refresh |
| **Project Insights** | Persist repo-specific conventions and notes (key/value CRUD scoped to module, symbol, or global) |

### Architecture Diagram

```
┌─────────────────────────────────────────────────────┐
│                  LLM Tools                           │
│  jvm_project_overview  jvm_symbol_search            │
│  jvm_file_outline      jvm_context_pack             │
│  jvm_dependency_trace  jvm_task_suggest             │
│  jvm_change_impact     jvm_failure_explain          │
│  jvm_verify_plan                                     │
└──────────┬──────────────────────────────────────────┘
           │ calls
┌──────────▼──────────────────────────────────────────┐
│              JvmIndexCoordinator                     │
│  (freshness check → incremental or full refresh)     │
└──────────┬──────────────────────────────────────────┘
           │ delegates to
┌──────────▼──────────────────────────────────────────┐
│              SqliteJvmProjectIndex                   │
│  (SQLite-backed: scanner + symbol index + deps)      │
└──────┬──────────┬──────────┬────────────────────────┘
       │          │          │
       ▼          ▼          ▼
  GradleProject  SqliteSymbol  ModuleDependency
  Scanner        Index        Graph
```

---

## 2. Data Models

### JvmProjectSnapshot

The top-level snapshot returned by every scan:

```
data class JvmProjectSnapshot(
    val projectDir: String,   // absolute path to project root
    val scannedAt: Long,       // System.currentTimeMillis() of scan
    val modules: List<ProjectModule>,
)
```

### ProjectModule

Each Gradle module discovered during scanning:

```
data class ProjectModule(
    val name: String,          // e.g. ":golem-core", ":" for root
    val path: String,          // absolute filesystem path
    val isRoot: Boolean,       // true for the root project
    val sourceDirs: List<String>,    // relative paths like "golem-core/src/main/kotlin"
    val testDirs: List<String>,      // relative paths like "golem-core/src/test/kotlin"
    val plugins: List<String>,       // e.g. "org.jetbrains.kotlin.jvm", "org.jetbrains.kotlin.plugin.serialization:1.9.0"
    val javaVersion: String?,        // e.g. "21" or "17"
    val kotlinVersion: String?,      // e.g. "1.9.0"
    val dependencies: List<String>,  // raw dependency strings including "testFramework:JUnit 5"
)
```

### SymbolLocation

Every symbol found in source files:

```
data class SymbolLocation(
    val name: String,           // symbol name (e.g. "MyClass", "processData")
    val kind: SymbolKind,       // see below
    val file: String,           // relative path from project root
    val line: Int,              // 1-based line number
    val column: Int,            // 1-based column offset
    val module: String,         // Gradle module name owning this file
    val visibility: String?,    // "public", "private", "internal", "protected", or null
)
```

### SymbolKind

Enum of all recognized symbol types:

```
CLASS, INTERFACE, OBJECT, FUN, VAL, VAR, ENUM, ANNOTATION
```

---

## 3. Project Scanning

### GradleProjectScanner

**Class:** `GradleProjectScanner`
**Method:** `fun scan(projectDir: String): List<ProjectModule>`

Starting from `projectDir`, the scanner:

1. Looks for `settings.gradle.kts` (falling back to `settings.gradle`)
2. Calls `BuildFileParsers.parseSettingsModules()` to extract `include(":moduleA", ":moduleB")` declarations
3. If the root `build.gradle.kts` exists (or no modules are declared), adds the root module (`:`, `isRoot=true`)
4. For each declared child module, resolves `include(":foo:bar")` to `<projectDir>/foo/bar/`, looks for `build.gradle.kts` there
5. Calls `BuildFileParsers.parseBuildFile()` on each module's build file to extract:
   - Plugins (via `id(...)`, `alias(libs.plugins.xxx)`, `kotlin("...")`)
   - Java version (via `JavaVersion.VERSION_21`, `jvmToolchain(21)`, `languageVersion.set(JavaLanguageVersion.of(21))`, `jvmTarget = "21"`)
   - Kotlin version (via `kotlin("jvm") version "1.9.0"`, `id("org.jetbrains.kotlin.jvm") version "1.9.0"`)
   - Dependencies (via `api(...)`, `implementation(...)`, `compileOnly(...)`, `testImplementation(...)`, `runtimeOnly(...)`, `testRuntimeOnly(...)`)
   - Custom source directories (via `srcDir(...)`, `srcDirs = [...]`)
6. Detects the test framework from dependencies (`Kotest`, `JUnit 5`, or `null`)
7. Discovers existing source directories: always checks `src/main/kotlin`, `src/main/java`, `src/test/kotlin`, `src/test/java` in addition to any custom dirs declared in the build file

**Source directory discovery** follows standard convention plus custom dirs:

```
src/main/kotlin    src/main/java      → sourceDirs
src/test/kotlin    src/test/java      → testDirs
+ any custom dirs declared in build.gradle.kts
```

### BuildFileParsers

**File:** `BuildFileParsers.kt`

Internal helpers used by the scanner:

- `parseSettingsModules(text: String): List<String>` — Extracts module names from `include(...)` statements. Handles both single-string and multi-argument forms, e.g. `include(":app")` or `include(":lib", ":tools")`.
- `parseBuildFile(text: String): ParsedBuildFile` — Extracts plugins, versions, dependencies, and source directories from a single `build.gradle.kts` file.
- `detectTestFramework(dependencies: List<String>): String?` — Returns `"Kotest"` or `"JUnit 5"` based on dependency names, or `null`.

**Multi-module vs single-module detection:** If `settings.gradle.kts` exists with `include()` statements, it's a multi-module project. If the file is missing or contains no includes, the scanner treats it as a single-module project (just root).

---

## 4. Symbol Extraction

Two extractors scan source files using regex-based parsing (stripping comments and string literals first to avoid false positives).

### KotlinSymbolExtractor

**Method:** `fun extract(file: Path, module: String, root: Path): List<SymbolLocation>`

Extracts from `.kt` files:

| Pattern | Matches |
|---------|---------|
| **Regular declarations** | `class`, `interface`, `object`, `fun`, `val`, `var`, `enum class`, `annotation class`, plus all modifiers (`data`, `sealed`, `open`, `abstract`, `inline`, `suspend`, `const`, `lateinit`, `tailrec`, `operator`, `infix`, `external`, `value`) |
| **Extension functions** | `fun ReceiverType.name(...)` |
| **Extension properties** | `val ReceiverType.name` / `var ReceiverType.name` |

Preserves visibility (`public`, `private`, `internal`, `protected`). Strips both block comments (`/*...*/`) and line comments (`//...`), and string/char literals before matching.

### JavaSymbolExtractor

**Method:** `fun extract(file: Path, module: String, root: Path): List<SymbolLocation>`

Extracts from `.java` files:

| Pattern | Matches |
|---------|---------|
| **Types** | `class`, `interface`, `enum`, `@interface` with visibility (`public`, `private`, `protected`) and modifiers (`abstract`, `final`, `static`) |
| **Methods** | Visibility + modifiers + return type + method name + parameter list + body block `{` |
| **Fields** | Visibility + modifiers + type + field name followed by `=` or `;` |

Filters out Java keywords (`if`, `for`, `while`, `switch`, `catch`, `return`, `new`) that would otherwise match as methods or fields. Deduplicates by kind+file+line+name.

### SqliteSymbolIndex

**Backing store for symbol search.** SQLite table `symbols`:

```
id         INTEGER PRIMARY KEY AUTOINCREMENT
module     VARCHAR(256)
name       VARCHAR(512)
kind       VARCHAR(64)    — one of SymbolKind enum values as string
file       VARCHAR(2048)  — relative to project root
line       INTEGER
column     INTEGER
visibility VARCHAR(64)    — nullable
```

**Key methods:**

- `suspend fun search(query: String, kind: SymbolKind? = null, module: String? = null): List<SymbolLocation>` — SQL `LIKE '%query%'` search on name, up to 100 results, ordered by module/file/line
- `suspend fun searchByModule(module: String): List<SymbolLocation>` — All symbols in a module
- `suspend fun searchByFile(file: String): List<SymbolLocation>` — All symbols in a specific file
- `suspend fun indexModule(module: ProjectModule, projectRoot: Path)` — Extract and store all symbols in a module
- `fun indexModuleInTransaction(module: ProjectModule, projectRoot: Path)` — Same, inside existing transaction
- `fun rebuildInTransaction(snapshot: JvmProjectSnapshot)` — Full reindex in one transaction
- `suspend fun replaceAll(snapshot: JvmProjectSnapshot)` — Full replace with deduplication

---

## 5. Dependency Analysis

### ModuleDependencyGraph

**Class:** `ModuleDependencyGraph`
**Constructor parameter:** `val cache: SqliteDependencyCache? = null`

Resolves inter-module dependencies declared via `project()` in build files.

**Key methods:**

- `fun directDependencies(modules: List<ProjectModule>): List<ModuleDependency>` — Reads `build.gradle.kts` for each module, extracts `project(":other")` references with their configuration type (`api`, `implementation`, `compileOnly`, `testImplementation`). Falls back to parsing the dependency string from `ProjectModule.dependencies` if the build file parse produces nothing.
- `fun resolveTransitiveDependencies(modules: List<ProjectModule>): Map<String, List<String>>` — BFS from each module through `directDependencies` to compute full transitive closure. Uses `linkedSetOf` to preserve insertion order and prevent cycles.
- `fun findAffectedModules(changedFiles: List<String>, modules: List<ProjectModule>, symbols: List<SymbolLocation>): List<String>` — Maps changed file paths to their owning modules, then walks the reverse dependency graph to find all modules that depend on changed modules. Returns the full transitive closure of affected modules.
- `fun parseDependenciesOutput(output: String): List<ModuleDependency>` — Parses `./gradlew dependencies` tree output. Extracts `--- project :module` and `--- group:artifact:version` lines, skipping `(*)` duplicate markers.
- `fun runDependenciesCommand(projectDir: String, module: String): String` — Executes `./gradlew :module:dependencies --configuration runtimeClasspath --no-daemon -q` with a 60-second timeout.
- `fun resolveWithDependenciesCommand(modules: List<ProjectModule>, projectDir: String): Map<String, List<ModuleDependency>>` — Runs the live Gradle command for each module, falls back to build-file parsing on failure or timeout.

### ModuleDependency

```
data class ModuleDependency(
    val moduleName: String,  // source module (e.g. ":golem-core")
    val dependency: String,  // target (e.g. ":golem-common") or external artifact string
    val type: String,        // configuration: "api", "implementation", "compileOnly", "testImplementation", "project", "external"
)
```

### SqliteDependencyCache

**Table:** `dependency_cache`

```
module_name  VARCHAR(256)  — unique index with dependency
dependency   VARCHAR(512)
type         VARCHAR(64)
```

Methods:

- `fun replaceAll(dependencies: List<ModuleDependency>)` — Clear + batch insert
- `fun list(): List<ModuleDependency>` — Read all cached entries

### GradleTaskCataloger

Maps modules and change types to Gradle task names.

- `fun suggestTasks(module: String, changeType: String): List<String>`

| changeType | Result |
|------------|--------|
| `"test"` | `:module:test` or `test` for root |
| `"build"` | `:module:build` or `build` for root |
| `"java"` | `:module:compileJava` or `compileJava` for root |
| `"src"` (default) | `:module:compileKotlin` or `compileKotlin` for root |

- `fun suggestTestTask(module: String, testClass: String?): String` — Returns `./gradlew :module:test --tests "com.example.MyTest"` if a test class is given, or just `./gradlew :module:test` otherwise.

---

## 6. Change Impact

### GitChangeCollector

**Class:** `GitChangeCollector`
**Constructor parameter:** `val workdir: Path = Path.of(System.getProperty("user.dir"))`

**Key methods:**

- `fun getChangedFiles(): List<ChangedFile>` — Executes `git diff --name-status HEAD`. Falls back to `git diff --cached --name-status` + `git diff --name-status` if HEAD doesn't exist (empty repo). Also runs `git diff --stat HEAD` to get per-file change summaries (insertions/deletions). Returns a `List<ChangedFile>` deduplicated by path.

- `fun getChangedSymbols(symbolIndex: SqliteSymbolIndex): List<SymbolLocation>` — For each changed file, queries the symbol index for symbols in that file.

### ChangedFile

```
data class ChangedFile(
    val path: String,           // file path relative to repo root
    val changeType: ChangeType, // ADDED, MODIFIED, DELETED
    val summary: String?,       // e.g. "5 ++\n2 --"
)
```

### ChangeType

```
enum class ChangeType { ADDED, MODIFIED, DELETED }
```

### ImpactAnalyzer

**Method:** `fun analyze(changedFiles: List<ChangedFile>, modules: List<ProjectModule>, depGraph: ModuleDependencyGraph, symbols: List<SymbolLocation>): ImpactReport`

The analysis pipeline:

1. Calls `depGraph.findAffectedModules()` with the changed file paths and symbols to get the full transitive closure of impacted modules
2. Determines compilation scope: for each impacted module, picks the right compile task (`compileJava` if Java files changed, `compileKotlin` otherwise)
3. Identifies likely affected tests: for modules owning changed test files, generates `--tests "FullyQualifiedClassName"` commands; for other modules, just `:module:test`
4. Produces a unified list of verification commands

### ImpactReport

```
data class ImpactReport(
    val changedFiles: List<ChangedFile>,
    val changedSymbols: List<SymbolLocation>,
    val impactedModules: List<String>,
    val likelyAffectedTests: List<String>,
    val compilationScope: List<String>,
    val verificationCommands: List<String>,
)
```

### TestSelectionEngine

`fun selectTests(impact: ImpactReport): List<String>` — Returns the likely affected tests from the impact report, or falls back to suggesting test tasks for all impacted modules.

---

## 7. Failure Explanation

### GradleFailureParser

**Method:** `fun parse(output: String): List<GradleFailure>`

Strips ANSI escape codes, then scans line-by-line for 6 failure types:

| Type | Detection | Fields populated |
|------|-----------|-----------------|
| `TASK` | `> Task :name FAILED` | task |
| `COMPILATION` | `file.kt:line:col: error: message` (Kotlin) or `file.java:line: error: message` (Java) | task, file, line, message |
| `CONFIGURATION` | `* What went wrong:` followed by message lines | task, message |
| `DEPENDENCY_RESOLUTION` | `Could not resolve` or `Failed to resolve` | task, message |
| `DAEMON` | `Could not start Gradle daemon`, `Daemon is busy`, `Gradle daemon stopped` | task, message |
| `TEST` | `ClassName > methodName FAILED` or `Tests failed:` / `Test failures:` | task, testClass, testMethod, message, expectedValue, actualValue, assertionMethod, stackTraceRoot |

For test failures, the parser extracts:
- Expected and actual values from `expected: <X> but was: <Y>` patterns
- Assertion method detection (`assertEquals`, `assertTrue`, `assertFalse`, `assertNull`, `assertNotNull`, `assertSame`, `assertNotSame`)
- Stack trace root from the first `at ...` line

### GradleFailure

```
data class GradleFailure(
    val type: GradleFailureType,
    val task: String?,
    val file: String?,
    val line: Int?,
    val message: String,
    val testClass: String?,
    val testMethod: String?,
    val stackTraceRoot: String?,
    val expectedValue: String?,
    val actualValue: String?,
    val assertionMethod: String?,
)
```

### JvmFailureExplainer

**Method:** `fun explain(failures: List<GradleFailure>, modules: List<ProjectModule>, symbols: List<SymbolLocation>): FailureReport`

Enriches parsed failures with:

1. **Symbol resolution** — Finds the nearest symbol above the error line in the same file (e.g., the enclosing class or function)
2. **Module mapping** — Maps the failing file to its Gradle module (via source directory matching)
3. **Fix suggestions** — Pattern-matches error messages against 10+ known patterns:

| Error message pattern | Suggestion |
|----------------------|------------|
| `Unresolved reference 'X'` | "Missing import for 'X'. Add `import <package>.X`." |
| `Type mismatch` | "Type mismatch — check the expected vs actual types." |
| `unresolved reference to` | "Missing or incorrect import." |
| `Only safe (?.) or non-null asserted` | "Use `?.` (safe call) or `!!` (assert non-null) operator." |
| `Cannot infer type` | "The compiler cannot infer the type. Add an explicit type annotation." |
| `does not have a constructor` | "The class '...' is an interface or abstract class. It cannot be instantiated directly." |
| `expects X arguments` | "Wrong number of arguments. Check the function/constructor signature." |
| `must be initialized` | "Property '...' must be initialized. Add `= defaultValue` or make it `lateinit`." |
| `cannot be cast` | "Class cast exception. The runtime type doesn't match the expected type." |
| `is not a member of` | "Method/property doesn't exist on this type. Check the type or method name." |

4. **Cross-module hints** — For `Unresolved reference` errors, suggests that the referenced type may exist in another module and warns about missing dependency declarations.

### FailureReport

```
data class FailureReport(
    val summary: String,                     // "N likely root cause(s) found."
    val rootCauses: List<FailureRootCause>,
    val suggestedFixCommands: List<String>,  // e.g. ["./gradlew :module:compileKotlin", "./gradlew :module:test"]
)

data class FailureRootCause(
    val module: String?,
    val file: String?,
    val symbol: String?,       // nearest enclosing symbol or test class name
    val message: String,       // error text + fix suggestion + cross-module hint if applicable
)
```

### JvmPatchPreflight

**Method:** `fun preflightCheck(changedFiles: List<String>, modules: List<ProjectModule>, depGraph: ModuleDependencyGraph): VerificationPlan`

After code edits, this produces a minimal verification plan:

```
data class VerificationPlan(
    val compilationCommand: String,   // e.g. "./gradlew :module:compileKotlin :other:compileKotlin"
    val testCommand: String,          // e.g. "./gradlew :module:test --tests \"com.example.MyTest\""
    val estimatedDuration: String,    // "short" (≤1 impacted module) or "medium"
)
```

---

## 8. Incremental Indexing

### JvmFileWatcher

**Class:** `JvmFileWatcher(debounceMs: Long = 300L)`

Uses Java `java.nio.file.WatchService` to monitor the project directory tree for file changes.

**Key features:**

- Registers `ENTRY_CREATE`, `ENTRY_DELETE`, `ENTRY_MODIFY` events recursively on all subdirectories
- Skips `.git`, `build`, and `.gradle` directories
- Tracks only files ending in `.kt`, `.java`, `build.gradle.kts`, `build.gradle`
- **300ms debounce** — collects changes for 300ms before releasing them, preventing cascading triggers during writes
- `start(projectDir: Path)` — Begins watching; idempotent if already watching the same directory
- `takeChangedPaths(): List<Path>` — Drains pending events and returns accumulated changed file paths (empty if within debounce window or nothing changed)
- Implements `Closeable` — cleans up the WatchService

### IndexFreshnessPolicy

Per-query staleness thresholds so the agent doesn't waste time reindexing for every tool call:

| Query type | Max age | Auto-refresh strategy |
|-----------|---------|----------------------|
| `symbol_search` | 5 minutes | Incremental via watcher |
| `dependency_trace` | 15 minutes | Incremental via watcher |
| `context_pack` | 15 minutes (fallback) | Incremental via watcher |
| `file_outline` | 15 minutes (fallback) | Incremental via watcher |
| `change_impact` | 15 minutes (fallback) | Incremental via watcher |
| `failure_explain` | 15 minutes (fallback) | Incremental via watcher |
| `verify_plan` | 15 minutes (fallback) | Incremental via watcher |
| `overview` | 60 minutes | Full reindex preferred |

```
data class FreshnessPolicy(
    val maxAgeMs: Long,
    val preferReindex: Boolean = false,
)
```

- `isStale(lastScannedAt: Long?): Boolean` — Returns `true` if `null` or age exceeds `maxAgeMs`

### JvmIndexCoordinator

**Class:** `JvmIndexCoordinator(freshnessPolicy, fileWatcher, autoRefresh, projectDirProvider)`

Orchestrates the decision between returning a cached snapshot, doing an incremental refresh, or performing a full rescan.

**Method:** `suspend fun ensureFresh(queryType: String, index: SqliteJvmProjectIndex): JvmProjectSnapshot`

Decision tree:

```
getSnapshot() → null? ──yes──→ full scan + start watcher → return
               │
               ▼ no
         is stale? ──no──→ return cached snapshot
               │
               ▼ yes
         autoRefresh? ──no──→ full rescan → return
               │
               ▼ yes
         takeChangedPaths() → empty? ──yes──→ preferReindex? ──yes──→ full rescan
                                         │                    └─no──→ return stale
                                         ▼ no
         index.refreshChangedPaths(paths) → return refreshed
```

**`refreshChangedPaths`** (on `SqliteJvmProjectIndex`):
- If any changed path is `build.gradle`, `build.gradle.kts`, or `settings.gradle.kts`, does a **full rescan**
- Otherwise, identifies which modules own the changed files and reindexes only those modules' symbols
- Updates `scannedAt` timestamp
- All operations happen in a single SQLite transaction

---

## 9. Project Insights — Convention Memory

### ProjectInsightStore

**SQLite table:** `project_insights`

```
id         INTEGER PRIMARY KEY AUTOINCREMENT
module     VARCHAR(256)  nullable — Gradle module scope
symbol     VARCHAR(512)  nullable — symbol/file scope
key        VARCHAR(256)  — insight name (e.g. "build_commands", "test_pattern")
value      TEXT          — insight value
created_at LONG
updated_at LONG
```

**Methods:**

- `fun save(module: String?, symbol: String?, key: String, value: String)` — Upserts: inserts or updates `updated_at` and `value` if the (module, symbol, key) triple already exists
- `fun search(module: String?, symbol: String?, key: String?): List<ProjectInsight>` — Filters on any combination of module, symbol, and key. Returns all rows ordered by most recently updated when no filters are provided.
- `fun delete(module: String?, symbol: String?, key: String): Int` — Deletes matching row; returns 0 if not found

---

## 10. LLM-Accessible Tools — Complete Reference

All tools are registered via `registerJvmTools(registry, index, coordinator)` in the `dev.spola.tools` package. Each tool automatically ensures index freshness before executing.

---

### jvm_project_overview

**Parameters:** none

Returns the full module tree including source roots, plugins, and Kotlin/Java versions. Output format:

```
Project: /home/user/project
Scanned at: 1715600000000
Modules (3):
- : (root)
  path: /home/user/project
  sources: golem-core/src/main/kotlin
  tests: golem-core/src/test/kotlin
  plugins: org.jetbrains.kotlin.jvm, org.jetbrains.kotlin.plugin.serialization:1.9.0
  versions: Java 21, Kotlin 1.9.0
- :golem-core
  path: /home/user/project/golem-core
  sources: golem-core/src/main/kotlin
  tests: golem-core/src/test/kotlin
- :golem-cli
  path: /home/user/project/golem-cli
```

---

### jvm_symbol_search

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `name` | STRING | yes | Symbol name or substring to search for |
| `kind` | STRING | no | Filter by kind: CLASS, INTERFACE, OBJECT, FUN, VAL, VAR, ENUM, ANNOTATION |
| `module` | STRING | no | Filter by Gradle module (e.g. `:golem-core`) |

Output: one line per matching symbol:
```
:golem-core CLASS ImpactAnalyzer golem-core/src/main/kotlin/.../ImpactAnalyzer.kt:12:1 public
:golem-core FUN analyze golem-core/src/main/kotlin/.../ImpactAnalyzer.kt:13:5 public
```

---

### jvm_file_outline

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `path` | STRING | yes | Source file path (absolute or relative to project working directory) |

Returns all symbols declared in the specified file. Security restriction: rejects paths outside the project root with `SecurityException`.

---

### jvm_context_pack

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `goal` | STRING | no | Optional keywords to focus the context summary |

Builds a compact project summary for LLM context injection. Includes module names, paths, plugins, versions, up to 5 goal-relevant dependencies per module, and up to 80 relevant symbols. Passes through `TokenJuice.compact()` for token efficiency, capped at 2000 characters.

Without a goal, returns the first 80 symbols across all modules. With a goal, extracts keywords (≥3 chars), searches for matching symbols, and filters dependencies by relevance.

---

### jvm_dependency_trace

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `module` | STRING | no | Filter to a specific module (e.g. `:app`) |
| `dependency` | STRING | no | Filter to a specific dependency (e.g. `:lib`) |
| `use_gradle_command` | BOOLEAN | no | If true, runs `./gradlew dependencies` for resolved tree (default: false) |

By default (without `use_gradle_command`), parses `build.gradle.kts` files. With the flag enabled, runs the live Gradle task for each module and includes external dependencies in the output.

Output:
```
Dependency graph:
- :golem-core
  implementation: :golem-common
  api: :golem-annotation
  transitive: :golem-annotation
- :golem-cli
  implementation: :golem-core
```

---

### jvm_task_suggest

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `change_type` | STRING | yes | One of: `src`, `test`, `build`, `java` |
| `module` | STRING | no | Gradle module name, defaults to root (`:`) |

Returns concrete `./gradlew` commands:
```
./gradlew :golem-core:compileKotlin
```

---

### jvm_change_impact

**Parameters:** none

Reads `git diff HEAD` (or staged/unstaged if no HEAD), maps file changes to modules via the dependency graph, and produces a full impact report.

Output:
```
Changed files:
- MODIFIED golem-core/src/main/kotlin/.../ImpactAnalyzer.kt (5 ++, 2 --)
Changed symbols:
- :golem-core CLASS ImpactAnalyzer .../ImpactAnalyzer.kt:12
- :golem-core FUN analyze .../ImpactAnalyzer.kt:13
Impacted modules: :golem-core, :golem-cli
Compilation scope: :golem-core:compileKotlin, :golem-cli:compileKotlin
Verification commands:
- ./gradlew :golem-core:compileKotlin
- ./gradlew :golem-core:test
- ./gradlew :golem-cli:compileKotlin
- ./gradlew :golem-cli:test
```

---

### jvm_failure_explain

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `output` | STRING | yes | Raw Gradle console output to analyze |

Parses the output, categorizes failures, matches symbols, and generates structured fix suggestions.

Output:
```
2 likely root cause(s) found.
Root causes:
- module=:golem-core file=.../MyService.kt symbol=MyClass: Unresolved reference 'SomeType'. Suggestion: Missing import for 'SomeType'. Add `import com.example.SomeType`. Cross-module hint: 'SomeType' may be defined in another module. Check if module ':golem-core' has the correct dependency declared.
- module=:golem-core file=.../MyServiceTest.kt symbol=MyServiceTest: expected: <42> but was: <0> (assertEquals)
Suggested commands:
- ./gradlew :golem-core:compileKotlin
- ./gradlew :golem-core:test
```

---

### jvm_verify_plan

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `paths` | STRING | yes | Comma-separated edited file paths |

Produces a minimal verification plan after code edits.

Output:
```
Compilation: ./gradlew :golem-core:compileKotlin
Tests: ./gradlew :golem-core:test
Estimated duration: short
```

---

### project_insight_save

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `module` | STRING | no | Gradle module scope |
| `symbol` | STRING | no | Symbol or file scope |
| `key` | STRING | yes | Insight key |
| `value` | STRING | yes | Insight value |

Save a repo-specific convention:
```
project_insight_save(key="build_commands", value="Always run :golem-core:test --stacktrace for full output")
```

---

### project_insight_search

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `module` | STRING | no | Filter by module |
| `symbol` | STRING | no | Filter by symbol |
| `key` | STRING | no | Filter by key |

Retrieve stored conventions.

---

### project_insight_delete

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `module` | STRING | no | Module scope |
| `symbol` | STRING | no | Symbol scope |
| `key` | STRING | yes | Key to delete |

Remove a stored insight.

---

## 11. Provenance Bundles

### ProvenanceBundle

Captures the complete history of a session for audit, debugging, or sharing:

```
data class ProvenanceBundle(
    val version: String,           // GolemVersion.VERSION
    val sessionId: String,
    val toolCalls: List<ProvenanceToolCall>,
    val codeDiff: String,          // git diff HEAD at checkpoint time
    val testResults: List<String>, // tool results containing "test"/"failed"/"passed"
    val model: String,             // LLM model name
    val timestamps: List<String>,  // checkpoint creation timestamps
    val metrics: ProvenanceMetrics,
)
```

**Creation:** `ProvenanceBundle.fromCheckpoint(manager, sessionId, metrics, model)` — reconstructs from checkpoint data.

**Formats:**
- `toJson()` — Pretty-printed JSON for machine consumption
- `toHtml()` — Self-contained HTML document with styled tables for human review

### ProvenanceTools

| Tool | Parameters | Description |
|------|-----------|-------------|
| `provenance_export` | `sessionId` (STRING, required), `format` (STRING, required — `json` or `html`) | Export a provenance bundle |
| `provenance_list` | none | List available session IDs |
| `provenance_info` | `bundleId` (STRING, required) | Show summary for a session |

---

## 12. CLI Commands

All under `golem project`:

```
golem project scan     — Force a full JVM project reindex
golem project overview — Print the JVM module tree
golem project symbol   — Lookup a JVM symbol
```

### golem project scan

Forces a full rescan. Output:
```
Indexed 3 module(s) in /home/user/project
: | sources=1 tests=1 deps=0
:golem-core | sources=1 tests=1 deps=12
:golem-cli | sources=1 tests=1 deps=5
```

### golem project overview

Reads the index (rescans if directory changed). Output:
```
Project: /home/user/project
Modules (2):
- : (root)
  path: /home/user/project
  sources: golem-core/src/main/kotlin
  tests: golem-core/src/test/kotlin
  plugins: org.jetbrains.kotlin.jvm
- :golem-cli
  path: /home/user/project/golem-cli
```

### golem project symbol <name>

| Option | Description |
|--------|-------------|
| `--module` | Filter by Gradle module, e.g. `:golem-core` |
| `--kind` | Filter by symbol kind |

Output:
```
:golem-core CLASS ImpactAnalyzer .../ImpactAnalyzer.kt:12:1
:golem-core FUN analyze .../ImpactAnalyzer.kt:13:5
```

---

## 13. Configuration

JVM intelligence is configured via `~/.golem/config.yaml` or CLI flags.

### Config file fields

| Field | Default | Description |
|-------|---------|-------------|
| `jvm-index-db` | `./.golem/jvm-index.db` | Path to the SQLite database for project index + symbol storage |
| `jvm-index-auto-refresh` | `true` | Enable incremental file watching and auto-refresh |

### CLI flags

| Flag | Description |
|------|-------------|
| `--jvm-index-db` | Override the JVM index database path |

### Default paths

- JVM index DB: `./.golem/jvm-index.db`
- Project insights DB: `./.golem/jvm-index.db.insights`
- Dependency cache DB: `./.golem/dependency-cache.db`

---

## 14. Architect Mode Integration

### JvmPlanningPrompts

Injects project context into the agent's planning loop:

- `fun planningPrompt(goal: String, contextPack: String?, impactReport: ImpactReport?)` — Constructs a prompt that includes the user's goal, optional JVM project context (from `jvm_context_pack`), and current change impact (from `jvm_change_impact`) with impacted modules and verification commands.
- `fun implementationBrief(goal: String, plan: String, dependencyTrace: String?)` — Constructs an implementation brief combining the goal, the architect's plan, and an optional dependency trace.

---

## 15. Complete Walkthrough: Fresh Clone to Production Edit

What happens when you start Golem in a fresh clone of a JVM project:

**Phase 1 — First tool call triggers scan**

```
$ git clone https://github.com/example/my-project
$ cd my-project
$ golem "add authentication module"

Agent calls jvm_project_overview
  → Snapshot is null → full scan triggered
  → GradleProjectScanner reads settings.gradle.kts → finds 4 modules
  → Each module's build.gradle.kts parsed → plugins, versions, deps
  → SqliteSymbolIndex walks all source dirs → extracts 1500+ symbols
  → JvmFileWatcher starts watching the project tree
  → Returns formatted module tree to LLM
```

**Phase 2 — Symbol search during planning**

```
Agent calls jvm_symbol_search(name="UserRepository", kind="CLASS")
  → Index is 1 second old, not stale → cached lookup
  → SQL: SELECT * FROM symbols WHERE name LIKE '%UserRepository%' AND kind='CLASS'
  → Returns class location at golem-core/src/.../UserRepository.kt:42
```

**Phase 3 — Dependency-aware editing**

```
Agent calls jvm_dependency_trace(module=":app")
  → Index is <5 min old → freshness check passes
  → ModuleDependencyGraph parses build.gradle.kts files
  → Discovers :app depends on :core, :lib which depends on :common
  → Returns dependency graph to LLM
```

**Phase 4 — Files changed, impact analyzed**

```
Agent writes code (go.mod edit, terminal git add, etc.)
Agent calls jvm_change_impact
  → GitChangeCollector runs git diff --name-status HEAD
  → Finds 3 modified files in :core + 1 in :lib
  → ImpactAnalyzer walks reverse dependency graph
  → Determines :app also affected (depends on :core)
  → Generates commands: ./gradlew :core:compileKotlin :lib:compileKotlin :app:compileKotlin
  → Generates test commands: ./gradlew :core:test :lib:test :app:test
```

**Phase 5 — Build fails, failure explained**

```
Agent runs build → gets Gradle output
Agent calls jvm_failure_explain(output="$GRADLE_OUTPUT")
  → GradleFailureParser finds COMPILATION error + TEST failure
  → JvmFailureExplainer matches "Unresolved reference"
  → Provides fix suggestion + cross-module hint
  → Returns structured report with suggested fix commands
```

**Phase 6 — File watcher keeps index fresh**

```
Developer saves UserRepository.kt in IDE
  → WatchService fires ENTRY_MODIFY event
  → JvmFileWatcher records pending change, starts debounce timer
  → After 300ms, takeChangedPaths() returns [UserRepository.kt]
  → Next tool call triggers JvmIndexCoordinator.ensureFresh()
  → refreshChangedPaths() reindexes only the :core module
  → Transaction commits: symbols for :core are updated atomically
```

**Phase 7 — Verification plan after fixes**

```
Agent calls jvm_verify_plan(paths="golem-core/src/.../UserRepository.kt, golem-core/src/.../UserService.kt")
  → JvmPatchPreflight checks impacted modules
  → Returns: ./gradlew :golem-core:compileKotlin, ./gradlew :golem-core:test
  → Estimated: "short" (single module affected)
```

---

## 16. Programmatic API (REST)

There are no dedicated project-intelligence REST endpoints. All JVM tools are exposed
through the generic tool execution API at:

```
POST /api/tools/run
{
  "tool": "jvm_project_overview",
  "arguments": {}
}
```

See the main API documentation for tool execution details.

---

## 17. Extending JVM Intelligence

### Adding a new symbol kind

1. Add a new value to `SymbolKind` enum
2. Update the Kotlin or Java extractor regex pattern
3. Update `String.toKind()` mappings in the extractors
4. SQLite handles it automatically (stored as string)

### Adding a new failure pattern

1. Add a new regex to `GradleFailureParser`
2. Add a corresponding `GradleFailureType` if it's a new category
3. Add a pattern match in `JvmFailureExplainer.fixSuggestion()`

### Adding a new tool

1. Add the tool registration in `registerJvmTools()` in `JvmProjectTools.kt`
2. Add a `FreshnessPolicy` constant in `IndexFreshnessPolicy` companion object
3. Register the query type in the `defaults` map
4. The `ensureSnapshot()` helper handles freshness automatically

---

## 18. File Index

| Source File | Purpose |
|-------------|---------|
| `jvm/JvmProjectSnapshot.kt` | Data models for snapshots and modules |
| `jvm/SymbolLocation.kt` | Symbol location data and SymbolKind enum |
| `jvm/JvmProjectIndex.kt` | Interface for the project index |
| `jvm/GradleProjectScanner.kt` | Parses settings/build files to discover modules |
| `jvm/KotlinSymbolExtractor.kt` | Regex-based extraction from .kt files |
| `jvm/JavaSymbolExtractor.kt` | Regex-based extraction from .java files |
| `jvm/SqliteJvmProjectIndex.kt` | SQLite-backed implementation of JvmProjectIndex |
| `jvm/SqliteSymbolIndex.kt` | SQLite-backed symbol storage and search |
| `jvm/ModuleDependencyGraph.kt` | Dependency resolution and impact analysis |
| `jvm/SqliteDependencyCache.kt` | Cached dependency storage |
| `jvm/GradleTaskCataloger.kt` | Module-to-Gradle-task mapping |
| `jvm/GitChangeCollector.kt` | Git diff parsing utilities |
| `jvm/ImpactAnalyzer.kt` | Changed-files to impacted-modules mapping |
| `jvm/GradleFailureParser.kt` | Gradle output parsing (6 failure types) |
| `jvm/JvmFailureExplainer.kt` | Structured failure reports with fix suggestions |
| `jvm/JvmPatchPreflight.kt` | Verification plan after code edits |
| `jvm/JvmPlanningPrompts.kt` | Context injection for architect mode |
| `jvm/JvmFileWatcher.kt` | Java WatchService with debounce (300ms) |
| `jvm/IndexFreshnessPolicy.kt` | Per-query staleness thresholds |
| `jvm/JvmIndexCoordinator.kt` | Incremental vs full refresh orchestration |
| `jvm/ProjectInsightStore.kt` | SQLite-backed convention/key-value store |
| `jvm/ProvenanceBundle.kt` | Session provenance JSON/HTML export |
| `jvm/BuildFileParsers.kt` | Gradle build file parsing helpers |
| `tools/JvmProjectTools.kt` | 9 LLM-accessible JVM intelligence tools |
| `tools/ProjectInsightTools.kt` | 3 insight CRUD tools |
| `tools/ProvenanceTools.kt` | 3 provenance export tools |
| `cli/ProjectCommands.kt` | CLI `golem project` subcommands |
| `config/ConfigLoader.kt` | Config loading (jvm-index-db path) |
