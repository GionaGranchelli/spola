# T-202: Git Tools

## Goal
Add git operations as Spola tools so the agent can read diffs, commit changes, check status, and view log.

## Requirements

### Tools to add

**git_diff**
- Parameters: path (optional, default "."), cached (optional boolean), head (optional string, e.g. "HEAD~1")
- Runs `git diff` in the workdir
- Respects SPOLA_ALLOWED_DIRS

**git_commit**
- Parameters: message (required)
- Runs `git add -A && git commit -m "<message>"`
- Returns success/failure with output

**git_status**
- Parameters: path (optional, default ".")
- Runs `git status --short`
- Returns formatted output

**git_log**
- Parameters: limit (optional, default 10)
- Runs `git log --oneline -n <limit>`
- Returns formatted output

### Security
- All tools check that workdir is inside SPOLA_ALLOWED_DIRS (empty = any dir)
- Git commands run via ProcessBuilder (no shell injection) — same pattern as ShellTool

### Registration
- Add `registerGitTools(registry)` in `Tools.kt` with the other tools
- No new dependencies — git must be installed on the host

### Tests
- Create temp dir, init git repo, make a change
- Test git_status shows modified files
- Test git_commit succeeds
- Test git_diff shows changes
- All existing 63 tests must pass

## Files
```
spola-backend-core/src/main/kotlin/dev/spola/tools/
├── GitTools.kt           — NEW

spola-backend-core/src/main/kotlin/dev/spola/tools/
├── Tools.kt              — MODIFY: add registerGitTools call

spola-backend-core/src/test/kotlin/dev/spola/tools/
└── GitToolsTest.kt       — NEW
```
