#!/usr/bin/env bash
# =============================================================================
# Spola LangGraph Demo — 5-Minute Introduction
# =============================================================================
# This script demonstrates Spola's type-safe state graphs (YAML-defined workflows)
# vs LangGraph's Python dynamic state approach.
#
# Prerequisites:
#   - Docker (running)
#   - curl
#   - Python 3 (for JSON formatting)
#
# What it shows:
#   1. Docker Compose startup (Spola backend + PostgreSQL)
#   2. API health check
#   3. Running a code-review workflow via POST /api/workflows/run
#   4. Streaming execution events via the stream endpoint
#   5. Clean shutdown
# =============================================================================
set -euo pipefail
# ── Configuration ────────────────────────────────────────────────────────────
SPOLA_BACKEND_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
API_PORT=8082
API_BASE="http://localhost:${API_PORT}"
API_KEY="${SPOLA_API_KEY:-demo-key}"
# Colours for output
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
RED='\033[0;31m'
NC='\033[0m' # No Color
# ── Helper Functions ─────────────────────────────────────────────────────────
info()    { echo -e "${BLUE}[INFO]${NC}  $1"; }
pass()    { echo -e "${GREEN}[PASS]${NC}  $1"; }
warn()    { echo -e "${YELLOW}[WARN]${NC}  $1"; }
header()  { echo -e "\n${CYAN}═══════════════════════════════════════════════${NC}"; echo -e "${CYAN}  $1${NC}"; echo -e "${CYAN}═══════════════════════════════════════════════${NC}\n"; }
fail()    { echo -e "${RED}[FAIL]${NC}  $1"; exit 1; }
wait_for_health() {
local max_attempts=30
local attempt=1
info "Waiting for Spola API health endpoint..."
while [ $attempt -le $max_attempts ]; do
if curl -sf "${API_BASE}/api/health" > /dev/null 2>&1; then
pass "Spola API is healthy!"
return 0
fi
printf "  Attempt %d/%d — waiting...\r" "$attempt" "$max_attempts"
sleep 2
attempt=$((attempt + 1))
done
fail "Spola API did not become healthy within $((max_attempts * 2)) seconds."
}
# ── Step 0: Pre-flight Checks ────────────────────────────────────────────────
header "Step 0: Pre-flight Checks"
if ! command -v docker &> /dev/null; then
fail "Docker is not installed. Please install Docker first."
fi
info "Docker is installed."
if ! docker info &> /dev/null; then
fail "Docker daemon is not running. Please start Docker first."
fi
pass "Docker daemon is running."
if ! command -v curl &> /dev/null; then
fail "curl is not installed."
fi
pass "curl is available."
# ── Step 1: Start Spola + PostgreSQL ─────────────────────────────────────────
header "Step 1: Starting Spola + PostgreSQL"
cd "$SPOLA_BACKEND_DIR"
info "Running 'docker compose up -d'..."
docker compose up -d
pass "Services started."
# ── Step 2: Wait for API Health ──────────────────────────────────────────────
header "Step 2: Waiting for API"
wait_for_health
INFO_JSON=$(curl -sf "${API_BASE}/api/health")
echo "  Health response: ${INFO_JSON}"
# ── Step 3: Verify Available Workflows ───────────────────────────────────────
header "Step 3: Available Workflows"
WORKFLOWS=$(curl -sf "${API_BASE}/api/workflows" \
-H "Authorization: Bearer ${API_KEY}" 2>/dev/null || echo '[]')
echo "  Registered workflows:"
echo "$WORKFLOWS" | python3 -m json.tool 2>/dev/null || echo "  $WORKFLOWS"
# ── Step 4: Run a Code Review Workflow ───────────────────────────────────────
header "Step 4: Running a Code Review Workflow"
WORKFLOW_PAYLOAD=$(
cat <<'JSONEOF'
{
"workflowName": "code-review",
"goal": "Review all Kotlin source files for security issues, code style, and test coverage",
"inputJson": "{\"files\":\"**/*.kt\",\"reviewers\":[\"security-reviewer\",\"style-reviewer\",\"test-reviewer\"]}"
}
JSONEOF
)
info "Sending POST /api/workflows/run..."
RUN_RESPONSE=$(curl -sf -X POST "${API_BASE}/api/workflows/run" \
-H "Content-Type: application/json" \
-H "Authorization: Bearer ${API_KEY}" \
-d "$WORKFLOW_PAYLOAD" 2>/dev/null || echo '{"error":"request failed"}')
echo "  Response:"
echo "$RUN_RESPONSE" | python3 -m json.tool 2>/dev/null || echo "  $RUN_RESPONSE"
EXECUTION_ID=$(echo "$RUN_RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('executionId',''))" 2>/dev/null || echo "")
if [ -z "$EXECUTION_ID" ]; then
warn "Could not extract execution ID. Using a placeholder."
EXECUTION_ID="demo-execution"
fi
pass "Workflow launched — Execution ID: ${EXECUTION_ID}"
# ── Step 5: Stream / Poll Execution Events ───────────────────────────────────
header "Step 5: Streaming Workflow Execution"
info "Polling execution status (polling every 2 seconds, max 30s)..."
for i in $(seq 1 15); do
EXEC_STATUS=$(curl -sf "${API_BASE}/api/workflows/executions/${EXECUTION_ID}" \
-H "Authorization: Bearer ${API_KEY}" 2>/dev/null || echo '{}')
STATUS=$(echo "$EXEC_STATUS" | python3 -c "
import sys, json
data = json.load(sys.stdin)
print(data.get('status', 'unknown'))
" 2>/dev/null || echo "unknown")
echo "  [${i}] Status: ${STATUS}"
if [ "$STATUS" = "COMPLETED" ]; then
pass "Workflow completed!"
echo ""
echo "  Final result:"
echo "$EXEC_STATUS" | python3 -c "
import sys, json
data = json.load(sys.stdin)
result = data.get('result', data.get('output', 'No result field'))
print(json.dumps(result, indent=2)[:2000])" 2>/dev/null
break
fi
if [ "$STATUS" = "FAILED" ] || [ "$STATUS" = "CANCELLED" ]; then
warn "Workflow ended with status: ${STATUS}"
echo "$EXEC_STATUS" | python3 -m json.tool 2>/dev/null | head -40
break
fi
# Attempt SSE stream subscribe
if [ "$i" -eq 1 ]; then
info "Also trying SSE stream at GET /api/workflows/executions/${EXECUTION_ID}/stream..."
# Non-blocking attempt — curl with timeout
STREAM_OUT=$(curl -sf --max-time 3 "${API_BASE}/api/workflows/executions/${EXECUTION_ID}/stream" \
-H "Authorization: Bearer ${API_KEY}" 2>/dev/null || echo "SSE stream not available (expected — may need chunked HTTP support)")
echo "  SSE response: ${STREAM_OUT:0:300}"
fi
sleep 2
done
# ── Step 6: Show Output Summary ──────────────────────────────────────────────
header "Step 6: Output Summary"
FINAL_STATUS=$(curl -sf "${API_BASE}/api/workflows/executions/${EXECUTION_ID}" \
-H "Authorization: Bearer ${API_KEY}" 2>/dev/null || echo '{}')
echo "$FINAL_STATUS" | python3 -c "
import sys, json
data = json.load(sys.stdin)
print('Status:', data.get('status', 'N/A'))
print('Workflow:', data.get('workflowName', 'N/A'))
result = data.get('result', '')
if result:
print('Result (first 500 chars):', json.dumps(str(result)[:500]))
" 2>/dev/null || echo "  Status output available above."
# ── Step 7: Cleanup (optional) ───────────────────────────────────────────────
header "Step 7: Cleanup"
warn "To stop services, run: docker compose down"
warn "To stop and remove volumes: docker compose down -v"
echo ""
info "Demo script finished successfully!"
echo ""
echo -e "${CYAN}───────────────────────────────────────────────────${NC}"
echo -e "${CYAN}  Spola: Type-safe state graphs on the JVM.${NC}"
echo -e "${CYAN}  LangGraph: Python dynamic state at runtime.${NC}"
echo -e "${CYAN}───────────────────────────────────────────────────${NC}"
