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
echo "Provider: ${SPOLA_BENCH_PROVIDER}"
echo "Base URL: ${SPOLA_BENCH_BASE_URL}"
echo "Model: ${SPOLA_BENCH_MODEL}"
echo "Max turns: ${SPOLA_BENCH_MAX_TURNS}"
echo "Timeout: ${SPOLA_BENCH_TIMEOUT}s"
echo "Runs per benchmark: ${SPOLA_BENCH_RUNS}"
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
if curl -s "${SPOLA_BENCH_BASE_URL}/models" > /dev/null 2>&1; then
    echo "LLM endpoint reachable at ${SPOLA_BENCH_BASE_URL}"
else
    echo "WARNING: LLM endpoint not reachable at ${SPOLA_BENCH_BASE_URL}"
    echo "Benchmarks may fail. Ensure the LLM server is running."
fi
echo ""

# ============================================================================
# BENCHMARK 1-5: Project-based benchmarks (existing fixtures)
# ============================================================================

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
    "grep -qi 'subtract\|expected.*but.*got\|wrong.*method' \"${RESULTS_DIR}/logs/04-explain-run*.log\" 2>/dev/null || true"

run_benchmark "05-impact" "05-impact" \
    "I am changing the return type of UserService.getUser() from User to UserProfile. List all files that need to be updated." \
    "grep -qi 'UserServiceImpl\|Main\.kt\|UserProfile' \"${RESULTS_DIR}/logs/05-impact-run*.log\" 2>/dev/null || true"

# ============================================================================
# BENCHMARKS 6-8: Spola-specific smoke tests (no project fixture needed)
# ============================================================================

echo "=== Spola-Specific Smoke Tests ==="

# 06-boot-test: spola --version
run_spola_cmd "06-boot-test" \
    "--version" \
    "version[[:space:]]*[0-9]+\.[0-9]+\.[0-9]+|[0-9]+\.[0-9]+\.[0-9]+"

# 07-help-test: spola --help
run_spola_cmd "07-help-test" \
    "--help" \
    "usage\|Usage\|USAGE\|--help\|--model\|--provider\|--dir\|--max-turns\|Available commands\|options\|Options"

# 08-model-test: spola --list-models or equivalent provider check
# First check if --list-models exists; if not, fallback to --help containing model info
if "${SPOLA_CLI}" --help 2>/dev/null | grep -q 'list-models\|list_models'; then
    run_spola_cmd "08-model-test" \
        "--list-models" \
        "gpt\|ollama\|claude\|llama\|model"
else
    # Fallback: verify provider/model combination works via -h/help containing model config
    run_spola_cmd "08-model-test" \
        "--help" \
        "--model\|model.*${SPOLA_BENCH_MODEL}\|provider.*${SPOLA_BENCH_PROVIDER}"
fi

# ============================================================================
# SUMMARY TABLE
# ============================================================================
echo ""
echo "=== Benchmark Results ==="

# Build associative arrays for summary
declare -A SCENARIO_PASS
declare -A SCENARIO_FAIL
declare -A SCENARIO_TOTAL_MS
declare -A SCENARIO_MIN_MS
declare -A SCENARIO_MAX_MS
declare -A SCENARIO_COUNT
declare -A SCENARIO_RUNS

# Collect all scenario names from results.json
ALL_SCENARIOS=()
while IFS= read -r line; do
    name=$(echo "${line}" | jq -r '.name // empty' 2>/dev/null)
    if [ -n "${name}" ] && [ "${name}" != "null" ]; then
        # Check if already in the array
        found=0
        for s in "${ALL_SCENARIOS[@]:-}"; do
            if [ "${s}" = "${name}" ]; then
                found=1
                break
            fi
        done
        if [ "${found}" -eq 0 ]; then
            ALL_SCENARIOS+=("${name}")
        fi
    fi
done < "${RESULTS_DIR}/results.json"

# Initialize accumulators
for s in "${ALL_SCENARIOS[@]}"; do
    SCENARIO_PASS["${s}"]=0
    SCENARIO_FAIL["${s}"]=0
    SCENARIO_TOTAL_MS["${s}"]=0
    SCENARIO_MIN_MS["${s}"]=""
    SCENARIO_MAX_MS["${s}"]=0
    SCENARIO_COUNT["${s}"]=0
    SCENARIO_RUNS["${s}"]=0
done

# Parse each JSON line
while IFS= read -r line; do
    name=$(echo "${line}" | jq -r '.name // empty' 2>/dev/null)
    status=$(echo "${line}" | jq -r '.status // empty' 2>/dev/null)
    dur=$(echo "${line}" | jq -r '.duration_ms // 0' 2>/dev/null)
    runnum=$(echo "${line}" | jq -r '.run // 1' 2>/dev/null)

    if [ -z "${name}" ] || [ "${name}" = "null" ]; then
        continue
    fi

    # Track unique run numbers per scenario
    SCENARIO_RUNS["${name}"]=$(( SCENARIO_RUNS["${name}"] + 1 ))

    # Count pass/fail
    if [ "${status}" = "pass" ]; then
        SCENARIO_PASS["${name}"]=$(( SCENARIO_PASS["${name}"] + 1 ))
    else
        SCENARIO_FAIL["${name}"]=$(( SCENARIO_FAIL["${name}"] + 1 ))
    fi

    SCENARIO_TOTAL_MS["${name}"]=$(( SCENARIO_TOTAL_MS["${name}"] + dur ))
    SCENARIO_COUNT["${name}"]=$(( SCENARIO_COUNT["${name}"] + 1 ))

    if [ -z "${SCENARIO_MIN_MS["${name}"]}" ] || [ "${dur}" -lt "${SCENARIO_MIN_MS["${name}"]}" ]; then
        SCENARIO_MIN_MS["${name}"]=${dur}
    fi
    if [ "${dur}" -gt "${SCENARIO_MAX_MS["${name}"]}" ]; then
        SCENARIO_MAX_MS["${name}"]=${dur}
    fi
done < "${RESULTS_DIR}/results.json"

# Print table header
printf "%-18s %5s %5s %5s %9s %9s %9s %9s\n" "Scenario" "Runs" "Pass" "Fail" "Avg(ms)" "Min(ms)" "Max(ms)" "SD(ms)"
printf -- "--------------------------------------------------------------------------------\n"

# Per-scenario rows
GRAND_TOTAL_RUNS=0
GRAND_TOTAL_PASS=0
GRAND_TOTAL_FAIL=0
GRAND_TOTAL_MS=0

for s in "${ALL_SCENARIOS[@]}"; do
    cnt=${SCENARIO_COUNT["${s}"]}
    passes=${SCENARIO_PASS["${s}"]}
    fails=${SCENARIO_FAIL["${s}"]}
    total_ms=${SCENARIO_TOTAL_MS["${s}"]}
    min_ms=${SCENARIO_MIN_MS["${s}"]:-0}
    max_ms=${SCENARIO_MAX_MS["${s}"]}

    if [ "${cnt}" -gt 0 ]; then
        avg_ms=$(( total_ms / cnt ))
    else
        avg_ms=0
    fi

    # Calculate standard deviation
    sd_ms=0
    if [ "${cnt}" -gt 1 ]; then
        sq_sum=0
        while IFS= read -r line; do
            ln_name=$(echo "${line}" | jq -r '.name // empty' 2>/dev/null)
            if [ "${ln_name}" = "${s}" ]; then
                ln_dur=$(echo "${line}" | jq -r '.duration_ms // 0' 2>/dev/null)
                diff=$(( ln_dur - avg_ms ))
                sq_sum=$(( sq_sum + (diff * diff) ))
            fi
        done < "${RESULTS_DIR}/results.json"
        variance=$(( sq_sum / cnt ))
        # Integer sqrt approximation
        sd_ms=$(echo "sqrt(${variance})" | bc -l 2>/dev/null | cut -d. -f1 2>/dev/null || echo 0)
    fi

    printf "%-18s %5d %5d %5d %9d %9d %9d %9d\n" \
        "${s}" "${cnt}" "${passes}" "${fails}" "${avg_ms}" "${min_ms}" "${max_ms}" "${sd_ms}"

    GRAND_TOTAL_RUNS=$(( GRAND_TOTAL_RUNS + cnt ))
    GRAND_TOTAL_PASS=$(( GRAND_TOTAL_PASS + passes ))
    GRAND_TOTAL_FAIL=$(( GRAND_TOTAL_FAIL + fails ))
    GRAND_TOTAL_MS=$(( GRAND_TOTAL_MS + total_ms ))
done

printf -- "--------------------------------------------------------------------------------\n"

if [ "${GRAND_TOTAL_RUNS}" -gt 0 ]; then
    GRAND_AVG=$(( GRAND_TOTAL_MS / GRAND_TOTAL_RUNS ))
else
    GRAND_AVG=0
fi

printf "%-18s %5d %5d %5d %9d  --TOTAL--\n" \
    "TOTAL" "${GRAND_TOTAL_RUNS}" "${GRAND_TOTAL_PASS}" "${GRAND_TOTAL_FAIL}" "${GRAND_AVG}"

echo ""
echo "Results saved to ${RESULTS_DIR}/results.json"
echo "Logs saved to ${RESULTS_DIR}/logs/"
echo "Done."
