# T-204: File Edit/Patch Tool

## Goal
Add an edit_file tool for targeted find-and-replace edits, complementary to write_file (which overwrites whole files).

## Requirements

### Tool: edit_file
- Parameters: path (required), oldText (required, the text to find), newText (required, the replacement), replaceAll (optional, boolean, default false)
- Read file → find unique occurrence of oldText → replace with newText → write back
- If replaceAll=false and oldText appears multiple times → fail with "found N occurrences, use replaceAll=true or be more specific"
- Returns diff showing changes (a few lines of context before and after)

### Implementation
- Uses java.nio.file.Files.readAllLines + String.replace or StringBuilder manipulation
- Fuzzy matching: normalize whitespace before matching, try exact first then whitespace-normalized
- Same security as FileTools: check SPOLA_ALLOWED_DIRS, resolve path, normalize

### Registration
- Add `registerEditTool(registry)` in `Tools.kt`

### Tests
- Create temp file, edit_file replaces single occurrence
- edit_file with ambiguous match (multiple occurrences, no replaceAll) → fail
- edit_file with replaceAll=true replaces all
- edit_file with non-existent text → fail
- edit_file with non-existent file → fail
- All existing 63 tests must pass

## Files
```
spola-backend-core/src/main/kotlin/dev/spola/tools/
├── EditTool.kt            — NEW

spola-backend-core/src/main/kotlin/dev/spola/tools/
├── Tools.kt               — MODIFY: add registerEditTool call

spola-backend-core/src/test/kotlin/dev/spola/tools/
└── EditToolTest.kt        — NEW
```
