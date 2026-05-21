#!/usr/bin/env python3
"""
Spola Python MCP Companion Server.

Provides Python execution tools to the Spola agent framework via MCP stdio transport.
Connects via stdin/stdout using the Model Context Protocol.

Tools:
  python_execute(code, timeout?)  — Execute Python code, return stdout/stderr/exit_code
  python_evaluate(expression, timeout?) — Evaluate a Python expression, return repr'd result
  python_packages(action, package?) — List, check, or install Python packages

Configuration in ~/.spola/mcp-servers.json:
  {
    "name": "python",
    "transport": "stdio",
    "command": "python3",
    "args": ["/path/to/spola-mcp-python-server.py"],
    "enabled": true
  }
"""

import subprocess
import sys
import json
from typing import Optional

# FastMCP from the mcp SDK provides a clean @tool decorator + stdio runner
try:
    from mcp.server.fastmcp import FastMCP
except ImportError:
    print(
        "MCP Python SDK not installed. Run: pip install mcp",
        file=sys.stderr,
    )
    sys.exit(1)

# ---------------------------------------------------------------------------
# Server setup
# ---------------------------------------------------------------------------

mcp = FastMCP(
    "spola-python",
    instructions="""Python execution server for Spola.

Provides three tools:
  - python_execute: run arbitrary Python code in a subprocess
  - python_evaluate: evaluate a single Python expression
  - python_packages: manage pip packages (list, check, install)

Security: code runs in a fresh subprocess each time. No sandboxing is applied.
Use with trusted code only.""",
)

# ---------------------------------------------------------------------------
# Helper
# ---------------------------------------------------------------------------


def _run_python(
    code: str,
    timeout: int,
    *,
    mode: str = "exec",
) -> dict:
    """Execute Python code in a subprocess and return structured results.

    Args:
        code: Python source code to run.
        timeout: Maximum seconds before the subprocess is killed.
        mode: "exec" for arbitrary statements, "eval" for a single expression.

    Returns:
        dict with keys: stdout, stderr, exit_code, error
    """
    if mode == "eval":
        # Wrap expression in print(repr(...))
        payload = f"import sys; print(repr({code}))"
    else:
        payload = code

    try:
        proc = subprocess.run(
            [sys.executable, "-c", payload],
            capture_output=True,
            text=True,
            timeout=timeout,
        )
        return {
            "stdout": proc.stdout,
            "stderr": proc.stderr,
            "exit_code": proc.returncode,
            "error": None,
        }
    except subprocess.TimeoutExpired:
        return {
            "stdout": "",
            "stderr": "",
            "exit_code": -1,
            "error": f"Execution timed out after {timeout}s",
        }
    except FileNotFoundError:
        return {
            "stdout": "",
            "stderr": "",
            "exit_code": -2,
            "error": f"Python interpreter not found: {sys.executable}",
        }
    except Exception as exc:
        return {
            "stdout": "",
            "stderr": "",
            "exit_code": -3,
            "error": f"Execution error: {exc}",
        }


def _format_result(result: dict) -> str:
    """Format the execution result dict into a readable string."""
    parts = []
    if result["stdout"]:
        parts.append(f"[stdout]\n{result['stdout'].rstrip()}")
    if result["stderr"]:
        parts.append(f"[stderr]\n{result['stderr'].rstrip()}")
    if result["error"]:
        parts.append(f"[error]\n{result['error']}")
    parts.append(f"[exit_code] {result['exit_code']}")
    return "\n\n".join(parts)


# ---------------------------------------------------------------------------
# Tool 1: python_execute
# ---------------------------------------------------------------------------


@mcp.tool(
    name="python_execute",
    description="Execute Python code in a subprocess and return stdout, stderr, and exit code.",
)
def python_execute(
    code: str,
    timeout: int = 30,
) -> str:
    """Execute Python code.

    Args:
        code: Python source code to execute.
        timeout: Maximum execution time in seconds (default 30, max 300).
    """
    timeout = min(max(timeout, 1), 300)
    result = _run_python(code, timeout, mode="exec")
    return _format_result(result)


# ---------------------------------------------------------------------------
# Tool 2: python_evaluate
# ---------------------------------------------------------------------------


@mcp.tool(
    name="python_evaluate",
    description="Evaluate a Python expression and return its repr'd result.",
)
def python_evaluate(
    expression: str,
    timeout: int = 10,
) -> str:
    """Evaluate a Python expression.

    The expression is evaluated via ``print(repr(<expression>))`` so the
    result is always printable.

    Args:
        expression: A single Python expression to evaluate.
        timeout: Maximum execution time in seconds (default 10, max 60).
    """
    timeout = min(max(timeout, 1), 60)
    result = _run_python(expression, timeout, mode="eval")
    return _format_result(result)


# ---------------------------------------------------------------------------
# Tool 3: python_packages
# ---------------------------------------------------------------------------


@mcp.tool(
    name="python_packages",
    description="Manage Python packages via pip: list installed, check if a package exists, or install one.",
)
def python_packages(
    action: str,
    package: Optional[str] = None,
) -> str:
    """Manage Python packages.

    Args:
        action: One of "list", "check", or "install".
        package: Package name (required for "check" and "install").
    """
    action = action.strip().lower()

    if action == "list":
        try:
            proc = subprocess.run(
                [sys.executable, "-m", "pip", "list", "--format=json"],
                capture_output=True,
                text=True,
                timeout=30,
            )
            if proc.returncode != 0:
                return f"Failed to list packages:\n{proc.stderr.strip()}"
            packages = json.loads(proc.stdout)
            lines = [f"Installed packages ({len(packages)}):"]
            for pkg in sorted(packages, key=lambda p: p["name"].lower()):
                lines.append(f"  - {pkg['name']} == {pkg['version']}")
            return "\n".join(lines)
        except json.JSONDecodeError as exc:
            return f"Failed to parse pip output: {exc}"
        except subprocess.TimeoutExpired:
            return "pip list timed out after 30s"
        except Exception as exc:
            return f"Error listing packages: {exc}"

    elif action == "check":
        if not package:
            return "Error: 'package' argument is required for 'check' action."
        pkg_name = package.strip()
        try:
            proc = subprocess.run(
                [sys.executable, "-m", "pip", "show", pkg_name],
                capture_output=True,
                text=True,
                timeout=15,
            )
            if proc.returncode == 0:
                # Parse version from output
                for line in proc.stdout.splitlines():
                    if line.lower().startswith("version:"):
                        ver = line.split(":", 1)[1].strip()
                        return f"Package '{pkg_name}' is installed (version {ver})."
                return f"Package '{pkg_name}' is installed."
            else:
                return f"Package '{pkg_name}' is NOT installed."
        except subprocess.TimeoutExpired:
            return f"pip show timed out for '{pkg_name}'."
        except Exception as exc:
            return f"Error checking package '{pkg_name}': {exc}"

    elif action == "install":
        if not package:
            return "Error: 'package' argument is required for 'install' action."
        pkg_spec = package.strip()
        try:
            proc = subprocess.run(
                [sys.executable, "-m", "pip", "install", pkg_spec],
                capture_output=True,
                text=True,
                timeout=120,
            )
            if proc.returncode == 0:
                return f"Successfully installed '{pkg_spec}'.\n{proc.stdout.strip()}"
            else:
                return f"Failed to install '{pkg_spec}':\n{proc.stderr.strip()}"
        except subprocess.TimeoutExpired:
            return f"pip install timed out after 120s for '{pkg_spec}'."
        except Exception as exc:
            return f"Error installing package '{pkg_spec}': {exc}"

    else:
        valid = ["list", "check", "install"]
        return f"Invalid action '{action}'. Valid actions: {', '.join(valid)}"


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

def main():
    """Start the MCP server on stdio transport.

    Reads JSON-RPC messages from stdin and writes responses to stdout.
    The server stays alive until stdin is closed.
    """
    if "--help" in sys.argv or "-h" in sys.argv:
        print(__doc__)
        return

    mcp.run(transport="stdio")


if __name__ == "__main__":
    main()
