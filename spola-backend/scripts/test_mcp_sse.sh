#!/usr/bin/env bash
set -euo pipefail
# Test Golem MCP server via SSE transport (HTTP-based, easier to test)

GOLEM_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$GOLEM_DIR"

# Build classpath
CP_FILE=$(mktemp /tmp/golem-cp.XXXXXX)
trap 'rm -f "$CP_FILE"' EXIT

find ~/.gradle/caches/modules-2/files-2.1 -name '*.jar' >> "$CP_FILE"
find "$GOLEM_DIR" -path '*/build/libs/golem-*.jar' -not -path '*test*' >> "$CP_FILE"
sed -i '/^$/d' "$CP_FILE"

JAVA_HOME="${JAVA_HOME:-$HOME/.sdkman/candidates/java/21.0.7-tem}"
JAVA="$JAVA_HOME/bin/java"

PORT=18091

echo "Starting Golem MCP SSE server on port $PORT..."
"$JAVA" @"$CP_FILE" dev.golem.cli.MainKt --mcp --mcp-transport sse --mcp-port $PORT &
PID=$!

# Wait for server to start
sleep 3

# Test 1: tools/list via JSON-RPC POST
echo "=== Test 1: tools/list ==="
TOOLS_RESPONSE=$(curl -s -X POST "http://localhost:$PORT/mcp" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' 2>/dev/null || echo "ERROR")

if [ "$TOOLS_RESPONSE" = "ERROR" ]; then
  echo "❌ Server not responding"
  kill $PID 2>/dev/null
  exit 1
fi

echo "$TOOLS_RESPONSE" | python3 -m json.tool
NUM_TOOLS=$(echo "$TOOLS_RESPONSE" | python3 -c "import sys,json; print(len(json.load(sys.stdin)['result']['tools']))" 2>/dev/null || echo "0")
echo "✅ Tools registered: $NUM_TOOLS"

# Test 2: read_file tool call
echo ""
echo "=== Test 2: read_file ==="
CONTENT=$(curl -s -X POST "http://localhost:$PORT/mcp" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"read_file","arguments":{"path":"AGENTS.md"}}}' 2>/dev/null)
echo "$CONTENT" | python3 -m json.tool 2>/dev/null || echo "$CONTENT"

# Cleanup
echo ""
echo "=== Shutting down ==="
kill $PID 2>/dev/null
wait $PID 2>/dev/null
echo "✅ Done"
