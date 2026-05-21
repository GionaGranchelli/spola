#!/usr/bin/env python3
"""Test Spola MCP server via stdio using java -cp (avoids Gradle's IO interception)."""

import json
import subprocess
import sys
import os
import time
import glob
import pathlib

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
SPOLA_BACKEND_DIR = os.path.join(SCRIPT_DIR, "..")

def find_jar(directory, pattern):
    """Find a jar file matching the pattern."""
    matches = glob.glob(os.path.join(directory, "build", "libs", pattern))
    return matches[0] if matches else None


def main():
    # Find Gradle cache for dependency jars
    gradle_cache = os.path.expanduser("~/.gradle/caches/modules-2/files-2.1")
    jars = []

    # Core jar
    core_jar = find_jar(os.path.join(SPOLA_BACKEND_DIR, "spola-backend-core"), "spola-backend-core-*.jar")
    if core_jar:
        jars.append(core_jar)

    # CLI jar
    cli_jar = find_jar(os.path.join(SPOLA_BACKEND_DIR, "spola-backend-cli"), "spola-backend-cli-*.jar")
    if cli_jar:
        jars.append(cli_jar)

    # Find all dependency jars from Gradle cache
    for root, dirs, files in os.walk(gradle_cache):
        for f in files:
            if f.endswith(".jar"):
                jars.append(os.path.join(root, f))

    if not jars:
        # Fallback: try to use Gradle's classpath
        print("FATAL: No jars found. Build the project first.", file=sys.stderr)
        sys.exit(1)

    classpath = ":".join(jars)

    # Build a more detailed MCP request
    req = json.dumps({
        "jsonrpc": "2.0",
        "id": 1,
        "method": "tools/list",
        "params": {}
    })

    java_home = os.environ.get("JAVA_HOME", "/home/gionag/.sdkman/candidates/java/21.0.7-tem")
    java = os.path.join(java_home, "bin", "java")

    proc = subprocess.Popen(
        [java, "-cp", classpath, "dev.spola.cli.MainKt", "--mcp", "--mcp-transport", "stdio"],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )

    # Wait for startup, send request
    time.sleep(1)
    proc.stdin.write(req + "\n")
    proc.stdin.flush()

    # Read response (server keeps running, so read with timeout)
    timeout = time.time() + 10
    response = None
    buf = ""
    while time.time() < timeout:
        # Check if data is available
        import select
        if select.select([proc.stdout], [], [], 0.5)[0]:
            line = proc.stdout.readline()
            if line:
                buf += line
                try:
                    response = json.loads(buf.strip())
                    break
                except json.JSONDecodeError:
                    continue

    # Kill the server
    proc.terminate()
    try:
        proc.wait(timeout=3)
    except:
        proc.kill()

    if response:
        print("=== MCP RESPONSE ===")
        print(json.dumps(response, indent=2))
        tools = response.get("result", {}).get("tools", [])
        print(f"\n✅ {len(tools)} tools registered:")
        for t in tools:
            props = t.get("inputSchema", {}).get("properties", {})
            print(f"   • {t['name']} — {len(props)} params")
        sys.exit(0)
    else:
        print("❌ No MCP response received", file=sys.stderr)
        print(f"Buffer: {buf[:500]}", file=sys.stderr)
        # Show last 5 stderr lines
        stderr_lines = []
        try:
            _, stderr = proc.communicate(timeout=2)
            for line in stderr.split("\n")[-5:]:
                if line.strip():
                    print(f"   STDERR: {line}", file=sys.stderr)
        except:
            pass
        sys.exit(1)


if __name__ == "__main__":
    main()
