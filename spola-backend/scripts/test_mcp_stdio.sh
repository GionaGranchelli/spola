#!/usr/bin/env bash
set +e
# Test Golem MCP server via stdio using bash coproc (bidirectional pipes)

GOLEM_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$GOLEM_DIR"

# Build classpath properly with : separator
JAVA_HOME="${JAVA_HOME:-$HOME/.sdkman/candidates/java/21.0.7-tem}"
JAVA="$JAVA_HOME/bin/java"

# Use gradle to get the classpath
echo "Getting classpath from Gradle..."
CP=$(./gradlew :golem-cli:dependencies --configuration runtimeClasspath -q 2>/dev/null | grep -- '---' | sed 's/.*--- //' | tr '\n' ':')
# Add project jars
CP="$CP:$(find "$GOLEM_DIR" -path '*/build/libs/golem-*.jar' -not -path '*test*' | tr '\n' ':')"

echo "Starting Golem MCP server..."
# Use coproc for bidirectional communication
coproc GOLEM { "$JAVA" -cp "$CP" dev.golem.cli.MainKt --mcp --mcp-transport stdio 2>/dev/null; }

# Send tools/list request
echo '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' >&${GOLEM[1]}

# Read response with timeout
if read -t 10 -u ${GOLEM[0]} line; then
    echo "=== RESPONSE ==="
    echo "$line" | python3 -m json.tool 2>/dev/null || echo "$line"
    TOOL_COUNT=$(echo "$line" | python3 -c "import sys,json; print(len(json.load(sys.stdin)['result']['tools']))" 2>/dev/null)
    echo ""
    echo "✅ Tools: $TOOL_COUNT"
else
    echo "❌ Timeout waiting for response"
fi

# Cleanup
kill ${GOLEM_PID} 2>/dev/null
wait ${GOLEM_PID} 2>/dev/null
