# Phase 5 — CLI Polish

## A. `--verbose`/`--debug` Flags

**Goal:** Add global flag-based verbosity control to the CLI and ConsoleObserver.

**Changes:**

1. **`Main.kt` — GolemCli:** Add two boolean options:
   - `--verbose` (counter or boolean)
   - `--debug` (boolean, implies verbose)

2. **`GolemConfig.kt`:** Add `verbosity: Verbosity = Verbosity.NORMAL` field, where `Verbosity` is:
   ```kotlin
   enum class Verbosity { NORMAL, VERBOSE, DEBUG }
   ```

3. **`ReplEngine.kt` — ConsoleObserver:** Make it accept a `verbosity` parameter (or get it from config). Modify observer methods:
   - **NORMAL** (default, existing behavior):
     - `onToolCall`: 🔧 tool → running... (args truncated 80 chars)
     - `onToolResult`: 🔧 tool ✓ or ✗
     - `onLlmCall`: 🤖 LLM call: provider/model
     - `onLlmResult`: ✅ LLM result: provider/model (in/out tokens)
     - `onError`: ✗ Error:
     - `onStatus`: NOT printed
     - `onToken`: NOT printed
   - **VERBOSE:**
     - Everything NORMAL shows
     - `onStatus(status, message)`: YES — printed
     - `onToken`: YES — streamed to console in real-time
     - Tool call args shown in full (no 80-char truncation)
   - **DEBUG:**
     - Everything VERBOSE shows
     - Tool call output: full result printed after execution
     - LLM request details (model, messages summary, tool count)
     - Memory operations printed

## B. `golem doctor` Subcommand

**Goal:** Diagnostic command that inspects the environment and reports issues.

**Implementation:** New file `DoctorCommand.kt` in `golem-cli`.

**picocli subcommand** of `GolemCli`:
```kotlin
@Command(name = "doctor", description = ["Diagnose configuration and environment"])
class DoctorCommand : Callable<Int>
```

**Checks:**
1. **Config file** — `~/.golem/config.yaml`: exists, valid YAML, parseable to GolemConfig
2. **Database paths** — Each DB path is writable (memory.db, scheduler.db, kanban.db, jvm-index.db, checkpoint.db)
3. **Environment variables** — Check required API keys for configured/default provider
4. **Provider connectivity** — If config is valid, try a lightweight ping (list models or simple completion)
5. **Persona file** — If set, verify it exists
6. **Skills directory** — If set, verify it exists
7. **JVM version** — Print java version

**Output format:** Color-coded:
- ✅ Pass
- ⚠️ Warning (non-fatal)
- ✗ Error (fatal)

## C. `golem config` Subcommand

**Goal:** View and edit the Golem config file from the CLI.

**Implementation:** New file `ConfigCommand.kt` in `golem-cli`.

**picocli subcommand** of `GolemCli` with subcommands:
```kotlin
@Command(name = "config", description = ["View and edit configuration"],
         subcommands = [ConfigShow::class, ConfigPath::class, ConfigSet::class, ConfigEdit::class])
class ConfigCommand : Callable<Int>
```

1. **`golem config show`** — Print current effective config as YAML (merged: file + CLI args + env vars)
2. **`golem config path`** — Print the config file path
3. **`golem config set <key> <value>`** — Set a config value in the YAML file (simple key=value, top-level only)
4. **`golem config edit`** — Open the config file in $EDITOR (or sensible default)

## Files to Create/Modify

**New files (in `golem-cli/src/main/kotlin/dev/golem/cli/`):**
- `DoctorCommand.kt` — the `golem doctor` subcommand
- `ConfigCommand.kt` — the `golem config` subcommand

**Modified files:**
- `Main.kt` — add `--verbose`, `--debug` flags; register DoctorCommand and ConfigCommand as subcommands; pass verbosity in buildConfig
- `ReplEngine.kt` — ConsoleObserver accepts verbosity level, respects it in all observer methods; also pass config's verbosity into REPL run
- `SlashCommand.kt` — StatusCommand can show verbosity level
- `GolemConfig.kt` — add verbosity field and Verbosity enum
- `JLineCompleter.kt` — (if exists) add completions for new commands

## Constraints
- Zero new JAR dependencies
- Follow existing picocli pattern (see SchedulerCommands.kt, AgentCommands.kt)
- DoctorCommand and ConfigCommand are pico CLI subcommands (not REPL slash commands)
- Build and test must pass (333+ tests, 0 failures)
- ConsoleObserver must remain binary-compatible with `AgentRunObserver` interface
