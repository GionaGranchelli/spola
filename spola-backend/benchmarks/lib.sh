#!/usr/bin/env bash
# shellcheck disable=SC1091
# Shared functions for Spola benchmark runner
set -euo pipefail

export JAVA_HOME="${JAVA_HOME:-/home/gionag/.sdkman/candidates/java/21.0.7-tem}"
export OPENAI_BASE_URL="${OPENAI_BASE_URL:-http://localhost:11434/v1}"
export OPENAI_API_KEY="${OPENAI_API_KEY:-not-needed}"

SPOLA_BACKEND_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SPOLA_CLI="${SPOLA_BACKEND_DIR}/spola-backend-cli/build/install/spola/bin/spola"
RESULTS_DIR="${SPOLA_BACKEND_DIR}/benchmarks/results"
TIMEOUT_SEC=300

run_benchmark() {
    local name="$1"
    local project="$2"
    local prompt="$3"
    local validate_cmd="$4"

    echo "--- Benchmark $name ---"

    # Create temp dir for this run
    local tmpdir
    tmpdir=$(mktemp -d)
    cp -r "${SPOLA_BACKEND_DIR}/benchmarks/projects/${project}/." "${tmpdir}/"

    # Ensure the .spola directory exists in the temp dir
    mkdir -p "${tmpdir}/.spola"

    # Run Spola in one-shot mode
    local start
    local end
    local duration_ms
    local result_file="${tmpdir}/spola_output.txt"

    start=$(date +%s%N)

    cd "${tmpdir}"
    set +e
    OPENAI_BASE_URL="${OPENAI_BASE_URL}" \
    OPENAI_API_KEY="${OPENAI_API_KEY}" \
    timeout "${TIMEOUT_SEC}" \
        "${SPOLA_CLI}" \
        --provider openai-compat \
        --model qwen2.5:0.5b \
        --max-turns 10 \
        --dir "${tmpdir}" \
        "${prompt}" > "${result_file}" 2>&1
    local exit_code=$?
    set -e

    end=$(date +%s%N)
    duration_ms=$(( (end - start) / 1000000 ))

    # Strip logback noise for display
    echo "  Spola exit code: ${exit_code}"
    echo "  Output (first 20 lines):"
    grep -v '^[0-9:.,-]\+\s*\[main\]\s' "${result_file}" | grep -v 'logback\|ch.qos\|^$' | head -20 | sed 's/^/    /'

    # Validate
    cd "${tmpdir}"
    set +e
    if eval "${validate_cmd}" 2>/dev/null; then
        echo "{\"name\":\"${name}\",\"status\":\"pass\",\"duration_ms\":${duration_ms}}" >> "${RESULTS_DIR}/results.json"
        echo "  >> PASS (${duration_ms}ms)"
    else
        echo "{\"name\":\"${name}\",\"status\":\"fail\",\"duration_ms\":${duration_ms}}" >> "${RESULTS_DIR}/results.json"
        echo "  >> FAIL (${duration_ms}ms)"
    fi
    set -e

    # Save full output for debugging
    mkdir -p "${RESULTS_DIR}/logs"
    cp "${result_file}" "${RESULTS_DIR}/logs/${name}.log"

    rm -rf "${tmpdir}"
    cd "${SPOLA_BACKEND_DIR}"
    echo ""
}
