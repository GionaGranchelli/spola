#!/usr/bin/env bash
set +e
# Test Spola MCP server via stdio using bash coproc (bidirectional pipes)

SPOLA_BACKEND_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$SPOLA_BACKEND_DIR"

# Build classpath properly with : separator
JAVA_HOME="${JAVA_HOME:-$HOME/.sdkman/candidates/java/21.0.7-tem}"
JAVA="$JAVA_HOME/bin/java"

# Use gradle to get the classpath
echo "Getting classpath from Gradle..."
CP=$(./gradlew :spola-backend-cli:dependencies --configuration runtimeClasspath -q 2>/dev/null | grep -- '---' | sed 's/.*--- //' | tr '\n' ':')
# Add project jars
CP="$CP:$(find "$SPOLA_BACKEND_DIR" -path '*/build/libs/spola-*.jar' -not -path '*test*' | tr '\n' ':')"

echo "Starting Spola MCP server..."
# Use coproc for bidirectional communication
coproc SPOLA_MCP { "$JAVA" -cp "$CP" dev.spola.cli.MainKt --mcp --mcp-transport stdio 2>/dev/null; }

# Send tools/list request
echo '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' >&${SPOLA_MCP[1]}

# Read response with timeout
if read -t 10 -u ${SPOLA_MCP[0]} line; then
    echo "=== RESPONSE ==="
    echo "$line" | python3 -m json.tool 2>/dev/null || echo "$line"
    TOOL_COUNT=$(echo "$line" | python3 -c "import sys,json; print(len(json.load(sys.stdin)['result']['tools']))" 2>/dev/null)
    echo ""
    echo "✅ Tools: $TOOL_COUNT"
else
    echo "❌ Timeout waiting for response"
fi

# Cleanup
kill ${SPOLA_MCP_PID} 2>/dev/null
wait ${SPOLA_MCP_PID} 2>/dev/null
