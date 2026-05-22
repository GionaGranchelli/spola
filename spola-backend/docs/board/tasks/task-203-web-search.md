# T-203: Web Search Tool

## Goal
Add a web search tool so Spola can search documentation, StackOverflow, and the web during coding tasks.

## Requirements

### Tool: web_search
- Parameters: query (required), maxResults (optional, default 5)
- Uses DuckDuckGo's instant answer API (no API key required): `https://api.duckduckgo.com/?q=<query>&format=json&no_html=1`
- Returns formatted results: title, URL, snippet
- Timeout: 10 seconds
- No new dependencies — uses java.net.URL or java.net.http.HttpClient

### Tool: web_fetch (bonus)
- Parameters: url (required), timeout (optional, default 10)
- Fetches HTML, strips tags, returns plain text (first 5000 chars max)
- Timeout protection  
- Respects SPOLA_ALLOWED_DIRS? No — it's outbound, not filesystem

### Registration
- Add `registerWebTools(registry)` in `Tools.kt`

### Tests
- Mock HTTP server (Ktor test host or java.net.HttpServer)
- Test web_search returns results
- Test web_search with no results
- Test web_fetch returns content
- Test web_fetch timeout
- All existing 63 tests must pass

## Files
```
spola-backend-core/src/main/kotlin/dev/spola/tools/
├── WebTools.kt            — NEW

spola-backend-core/src/main/kotlin/dev/spola/tools/
├── Tools.kt               — MODIFY: add registerWebTools call

spola-backend-core/src/test/kotlin/dev/spola/tools/
└── WebToolsTest.kt        — NEW
```
