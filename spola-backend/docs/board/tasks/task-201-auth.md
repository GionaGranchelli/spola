# T-201: API/MCP Authentication

## Goal
Add auth to REST API and MCP SSE mode so tools are not exposed without a key.

## Requirements

### CLI flag
```
--api-key <secret>   # If set, every HTTP request requires Authorization: Bearer <secret>
                     # MCP SSE also requires the key as ?apiKey=<secret> query param
```

### REST API auth
- Ktor plugin `StatusPages` catches missing/wrong key
- Guard checking header `Authorization: Bearer <secret>` vs configured key
- No auth guard when --api-key is NOT set (backward compat)
- If key is set:
  - No key → 401 `{"error":"missing api key"}`
  - Wrong key → 403 `{"error":"invalid api key"}`
  - Valid key → 200 (pass through)

### MCP SSE auth
- In SSE mode, check query param `apiKey` or header `X-Api-Key`
- Same guard logic: no key set = open, key set = validate

### Tests
- Request without key → 401
- Request with wrong key → 403
- Request with valid key → 200
- MCP SSE connection without key → rejected
- All existing 63 tests must pass (no regressions)

## Files to modify
```
spola-backend-core/src/main/kotlin/dev/spola/api/
├── SpolaApiServer.kt     — add auth middleware
├── ApiAuth.kt            — NEW: auth guard logic

spola-backend-core/src/test/kotlin/dev/spola/api/
└── SpolaApiServerTest.kt — add auth tests
```
