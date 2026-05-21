#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/lib.sh"

RESULTS_DIR="${SPOLA_BACKEND_DIR}/benchmarks/results"
mkdir -p "${RESULTS_DIR}"
mkdir -p "${RESULTS_DIR}/logs"

# Initialize results file (empty, each benchmark appends one JSON line per run)
true > "${RESULTS_DIR}/results.json"

echo "=== Spola Benchmark Suite ==="
echo "Provider: openai-compat"
echo "Base URL: ${OPENAI_BASE_URL}"
echo "Model: qwen2.5:0.5b"
echo "Max turns: 10"
echo "Timeout: ${TIMEOUT_SEC}s"
echo ""

# Build Spola first
echo "Building Spola..."
cd "${SPOLA_BACKEND_DIR}"
JAVA_HOME="${JAVA_HOME}" ./gradlew :spola-backend-cli:installDist -q 2>&1
echo "Spola built successfully."
echo ""

# Verify Spola CLI exists
if [ ! -f "${SPOLA_CLI}" ]; then
    echo "ERROR: Spola CLI not found at ${SPOLA_CLI}"
    exit 1
fi

# Verify LLM endpoint is reachable
echo "Checking LLM endpoint..."
if curl -s "${OPENAI_BASE_URL}/models" > /dev/null 2>&1; then
    echo "LLM endpoint reachable at ${OPENAI_BASE_URL}"
else
    echo "WARNING: LLM endpoint not reachable at ${OPENAI_BASE_URL}"
    echo "Benchmarks may fail. Ensure Ollama/LLM server is running."
fi
echo ""

# Run each benchmark
run_benchmark "01-refactor" "01-refactor" \
    "Rename the class StringUtils to TextUtils across all modules. Make sure the project still compiles." \
    "./gradlew build --no-daemon -q 2>/dev/null && ! grep -r 'StringUtils' common/src/ app/src/ 2>/dev/null"

run_benchmark "02-debug" "02-debug" \
    "The project has a compilation error. Find and fix it. Run ./gradlew build to verify." \
    "./gradlew build --no-daemon -q 2>/dev/null"

run_benchmark "03-migrate" "03-migrate" \
    "Migrate this project from JUnit 4 to JUnit 5. Update both the build file and the test files." \
    "./gradlew test --no-daemon -q 2>/dev/null && ! grep -r 'junit:junit' build.gradle.kts 2>/dev/null"

run_benchmark "04-explain" "04-explain" \
    "Run the tests, read the failure output, and explain what's wrong with the test." \
    "grep -qi 'subtract\|expected.*but.*got\|wrong.*method' \"${RESULTS_DIR}/logs/04-explain.log\" 2>/dev/null || true"

run_benchmark "05-impact" "05-impact" \
    "I am changing the return type of UserService.getUser() from User to UserProfile. List all files that need to be updated." \
    "grep -qi 'UserServiceImpl\|Main\.kt\|UserProfile' \"${RESULTS_DIR}/logs/05-impact.log\" 2>/dev/null || true"

# Summary
echo "=== Results ==="
if [ -s "${RESULTS_DIR}/results.json" ]; then
    cat "${RESULTS_DIR}/results.json"
else
    echo "(no results)"
fi
echo ""

# Parse results
echo "=== Summary ==="
pass_count=$(grep -c '"pass"' "${RESULTS_DIR}/results.json" 2>/dev/null || echo 0)
fail_count=$(grep -c '"fail"' "${RESULTS_DIR}/results.json" 2>/dev/null || echo 0)
echo "Passed: ${pass_count} / Failed: ${fail_count}"
echo "Results saved to ${RESULTS_DIR}/results.json"
echo "Logs saved to ${RESULTS_DIR}/logs/"
echo ""
echo "Done."
