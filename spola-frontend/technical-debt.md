# Technical Debt

This file captures the main issues found during the code review, along with practical fixes and tests to strengthen the application.

## 1. RootComponent is too large
**Finding**
- `composeApp/src/commonMain/kotlin/it/openclaw/app/decompose/RootComponent.kt` is ~950 lines.
- It owns session state, trust, files, commands, streaming, retries, and persistence orchestration.

**Advice**
- Split it into smaller Decompose child components, for example:
  - SessionComponent
  - ChatComponent
  - CommandComponent
  - TrustComponent
  - FileComponent
  - SettingsComponent
- Keep RootComponent as an orchestrator only.

**Tests**
- Unit test each child component independently.
- Add navigation/state restoration tests for selected session, trust, and streaming reconnect behavior.

## 2. App.kt is monolithic
**Finding**
- `composeApp/src/commonMain/kotlin/it/openclaw/app/App.kt` is ~843 lines.
- Many composables are deeply nested and parameter-heavy.

**Advice**
- Extract reusable UI pieces into smaller composables.
- Replace multiple boolean menu flags with a small UI state model.
- Introduce shared dropdown/select components.

**Tests**
- Compose UI tests for session list, settings dropdowns, trust controls, and command approval flow.

## 3. Pairing endpoint lacks auth hardening
**Finding**
- `/pairing/request` returns trust payload without Bearer auth.

**Advice**
- Require authorization there too, or restrict the endpoint to localhost/local pairing only.

**Tests**
- Integration test that unauthenticated pairing request is rejected.
- Integration test that valid trusted requests still work.

## 4. Command approvals are in-memory only
**Finding**
- `CommandManager` stores approvals in a `ConcurrentHashMap`.
- Pending approvals vanish on restart.

**Advice**
- Persist approvals in SQLite with explicit status.
- Add cleanup for stale approvals on startup.

**Tests**
- Restart/persistence test for pending and approved commands.
- Test approval invalidation after trust rotation/revoke.

## 5. FlowManager can leak flows
**Finding**
- Session and command flows are stored in maps and not cleaned up consistently.

**Advice**
- Remove flows on disconnect/completion.
- Consider TTL or lifecycle-bound cleanup.

**Tests**
- Flow lifecycle test for create, use, cleanup.
- Memory regression test for repeated session/command creation.

## 6. No multi-turn chat context
**Finding**
- Chat providers currently send only the latest prompt, not session history.

**Advice**
- Build a message history window from prior session messages.
- Use an API that supports chat history where possible.

**Tests**
- Verify provider payload includes prior assistant/user messages.
- Integration test for conversation continuity.

## 7. Desktop schema setup is duplicated
**Finding**
- `DriverFactory` manually recreates schema that also exists in SQLDelight.

**Advice**
- Use SQLDelight schema creation/migration directly.
- Remove duplicate DDL.

**Tests**
- Schema bootstrap test against in-memory DB.
- Migration test when schema evolves.

## 8. Model/session foreign-key mismatch risk
**Finding**
- Sessions reference `modelId`, but models are runtime catalog data.

**Advice**
- Either persist catalog models before session creation or relax the FK.

**Tests**
- Create session with catalog-only model.
- Ensure catalog refresh does not break existing sessions.

## 9. File pull can load large content into memory
**Finding**
- `file.readText()` is used for pulls.

**Advice**
- Add file size limits and stream large files.
- Prefer file download responses for bigger files.

**Tests**
- Large file pull test.
- Path traversal test.
- Outside-root rejection test.

## 10. Test coverage is thin
**Finding**
- Current tests cover only a small slice of the backend.

**Advice**
- Add tests for repositories, auth, trust rotation, file transfer, chat routing, and SSE behavior.

**High-value tests to add**
- `CommandManagerTest`
- `ChatRoutingServiceTest`
- `TrustRoutesIntegrationTest`
- `FileRoutesIntegrationTest`
- `SessionRepositoryTest`
- `AppStateStoreTest` for trust/session settings
- `ModelCatalogServiceTest`
- `OpenClawClient` contract tests

## Priority order
1. Split RootComponent
2. Extract App.kt composables
3. Fix pairing auth
4. Persist approvals
5. Add chat history
6. Clean up flow lifecycle
7. Remove schema duplication
8. Add broader tests

## Suggested near-term goal
- Reduce the largest files first.
- Then add tests around the security and approval paths.
- Finally, improve chat history and lifecycle cleanup.
