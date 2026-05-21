#!/usr/bin/env bash
# shellcheck disable=SC1091
# Shared functions for Spola benchmark runner
set -euo pipefail

# Use existing JAVA_HOME, or auto-detect from sdkman or system JDK
if [ -z "${JAVA_HOME:-}" ]; then
    if [ -n "${SDKMAN_DIR:-}" ] && [ -f "${SDKMAN_DIR}/candidates/java/current/bin/java" ]; then
        JAVA_HOME="${SDKMAN_DIR}/candidates/java/current"
    elif command -v java &>/dev/null; then
        JAVA_HOME="$(java -cp /dev/null -XshowSettings:vm 2>&1 | grep 'java.home' | awk '{print $2}' || true)"
    fi
fi
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"

# ---- Environment-driven configuration with sensible defaults ----
export SPOLA_BENCH_MODEL="${SPOLA_BENCH_MODEL:-gpt-4o-mini}"
SPOLA_BENCH_PROVIDER="${SPOLA_BENCH_PROVIDER:-openai-compat}"
SPOLA_BENCH_BASE_URL="${SPOLA_BENCH_BASE_URL:-http://localhost:11434/v1}"
SPOLA_BENCH_MAX_TURNS="${SPOLA_BENCH_MAX_TURNS:-10}"
SPOLA_BENCH_TIMEOUT="${SPOLA_BENCH_TIMEOUT:-300}"
SPOLA_BENCH_RUNS="${SPOLA_BENCH_RUNS:-3}"

export OPENAI_BASE_URL="${SPOLA_BENCH_BASE_URL}"
export OPENAI_API_KEY="${OPENAI_API_KEY:-not-needed}"

SPOLA_BACKEND_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SPOLA_CLI="${SPOLA_BACKEND_DIR}/spola-backend-cli/build/install/spola/bin/spola"
RESULTS_DIR="${SPOLA_BACKEND_DIR}/benchmarks/results"

run_benchmark() {
    local name="$1"
    local project="$2"
    local prompt="$3"
    local validate_cmd="$4"

    echo "--- Benchmark $name (${SPOLA_BENCH_RUNS} runs) ---"

    for run_idx in $(seq 1 "${SPOLA_BENCH_RUNS}"); do
        echo "  Run ${run_idx}/${SPOLA_BENCH_RUNS}..."

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
        OPENAI_BASE_URL="${SPOLA_BENCH_BASE_URL}" \
        OPENAI_API_KEY="${OPENAI_API_KEY}" \
        timeout "${SPOLA_BENCH_TIMEOUT}" \
            "${SPOLA_CLI}" \
            --provider "${SPOLA_BENCH_PROVIDER}" \
            --model "${SPOLA_BENCH_MODEL}" \
            --max-turns "${SPOLA_BENCH_MAX_TURNS}" \
            --dir "${tmpdir}" \
            "${prompt}" > "${result_file}" 2>&1
        local exit_code=$?
        set -e

        end=$(date +%s%N)
        duration_ms=$(( (end - start) / 1000000 ))

        # Strip logback noise for display
        echo "    Exit code: ${exit_code}"
        echo "    Output (first 20 lines):"
        grep -v '^[0-9:.,-]\+\s*\[main\]\s' "${result_file}" | grep -v 'logback\|ch.qos\|^$' | head -20 | sed 's/^/      /'

        # ----- Metrics extraction -----
        local tokens="null"
        local turns="null"

        # Extract token count from output lines like "Tokens: 1234" or "total_tokens: 1234"
        if grep -qi 'total.tokens\|tokens:' "${result_file}" 2>/dev/null; then
            tokens=$(grep -oi 'total.tokens[[:space:]]*[:=][[:space:]]*[0-9]\+' "${result_file}" 2>/dev/null | head -1 | grep -oE '[0-9]+' | head -1)
            tokens="${tokens:-null}"
        fi

        # Extract turn count from lines like "Turn: 5" or "Turns: 8" or "iteration"
        if grep -qi 'turns[[:space:]]*:=\|turns[[:space:]]*:\|number.of.turns\|turn #[0-9]\|iteration' "${result_file}" 2>/dev/null; then
            turns=$(grep -oi 'turns[[:space:]]*[:=][[:space:]]*[0-9]\+' "${result_file}" 2>/dev/null | head -1 | grep -oE '[0-9]+' | head -1)
            turns="${turns:-null}"
        fi

        # Also try parsing from the last line of structured output
        if [ "${turns}" = "null" ]; then
            local turn_matches
            turn_matches=$(grep -c -i 'tool.call\|function.call\|assistant.*message' "${result_file}" 2>/dev/null || echo 0)
            if [ "${turn_matches}" -gt 0 ]; then
                turns="${turn_matches}"
            fi
        fi

        # ----- Validate -----
        local status="fail"
        cd "${tmpdir}"
        set +e
        if eval "${validate_cmd}" 2>/dev/null; then
            status="pass"
        fi
        set -e

        # ----- Write JSON result -----
        local json_line
        json_line=$(cat <<JSONEOF
{"name":"${name}","run":${run_idx},"status":"${status}","duration_ms":${duration_ms},"exit_code":${exit_code},"turns":${turns},"tokens":${tokens}}
JSONEOF
)
        echo "${json_line}" >> "${RESULTS_DIR}/results.json"
        echo "    >> ${status^^} (${duration_ms}ms, exit=${exit_code}, turns=${turns}, tokens=${tokens})"

        # Save full output for debugging
        mkdir -p "${RESULTS_DIR}/logs"
        cp "${result_file}" "${RESULTS_DIR}/logs/${name}-run${run_idx}.log"

        rm -rf "${tmpdir}"
        cd "${SPOLA_BACKEND_DIR}"
    done
    echo ""
}

# Run Spola directly with a command (for smoke/version tests - no project dir needed)
run_spola_cmd() {
    local name="$1"
    local spola_args="$2"
    local validate_pattern="$3"

    echo "--- Benchmark $name (${SPOLA_BENCH_RUNS} runs) ---"

    for run_idx in $(seq 1 "${SPOLA_BENCH_RUNS}"); do
        echo "  Run ${run_idx}/${SPOLA_BENCH_RUNS}..."

        local start
        local end
        local duration_ms
        local result_file
        result_file=$(mktemp)

        start=$(date +%s%N)

        set +e
        # Evaluate spola_args so callers can pass compound expressions
        eval "OPENAI_BASE_URL='${SPOLA_BENCH_BASE_URL}' \
              OPENAI_API_KEY='${OPENAI_API_KEY}' \
              timeout '${SPOLA_BENCH_TIMEOUT}' \
              '${SPOLA_CLI}' ${spola_args}" > "${result_file}" 2>&1
        local exit_code=$?
        set -e

        end=$(date +%s%N)
        duration_ms=$(( (end - start) / 1000000 ))

        local output_text
        output_text=$(cat "${result_file}")

        echo "    Exit code: ${exit_code}"
        echo "    Output:"
        echo "${output_text}" | head -10 | sed 's/^/      /'

        # ----- Metrics from output -----
        local tokens="null"
        local turns="null"

        # Validate
        local status="fail"
        if echo "${output_text}" | grep -qiE "${validate_pattern}" 2>/dev/null; then
            status="pass"
        fi

        # JSON result
        local json_line
        json_line=$(cat <<JSONEOF
{"name":"${name}","run":${run_idx},"status":"${status}","duration_ms":${duration_ms},"exit_code":${exit_code},"turns":${turns},"tokens":${tokens}}
JSONEOF
)
        echo "${json_line}" >> "${RESULTS_DIR}/results.json"
        echo "    >> ${status^^} (${duration_ms}ms, exit=${exit_code})"

        # Save log
        mkdir -p "${RESULTS_DIR}/logs"
        cp "${result_file}" "${RESULTS_DIR}/logs/${name}-run${run_idx}.log"

        rm -f "${result_file}"
        cd "${SPOLA_BACKEND_DIR}"
    done
    echo ""
}
