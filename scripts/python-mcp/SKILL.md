# SKILL: Python MCP Server

> **Name:** `python-mcp-server`
> **Description:** Run Python code, evaluate expressions, and manage packages via an MCP stdio server.
> **Trigger:** When the agent needs to execute Python code or manage Python packages.
> **Server path:** `spola-backend-core/src/main/scripts/spola-mcp-python-server.py`

## Overview

This skill provides three Python execution tools to the agent via Spola's MCP Client.
The **Python MCP Companion Server** is a Python script that connects via stdin/stdout
and registers tools for executing code, evaluating expressions, and managing pip packages.

## Configuration

The server must be registered in `~/.spola/mcp-servers.json`:

```json
[
  {
    "name": "python",
    "transport": "stdio",
    "command": "python3",
    "args": ["/path/to/spola-backend-core/src/main/scripts/spola-mcp-python-server.py"],
    "enabled": true
  }
}
```

After configuration, restart Spola or run `spola mcp list` to verify connection.

## Available Tools

Once connected, the following tools become available with the `mcp_python_` prefix:

### `mcp_python_execute`

Run Python code in a fresh subprocess.

```
Arguments:
  code     (string, required)  — Python source code to execute
  timeout  (integer, optional) — Max seconds (1–300, default: 30)

Returns: stdout, stderr, exit code, and any error messages.
```

**Use cases:**
- Run calculations, data processing, or transformations
- Test Python code behavior
- Execute scripts that don't need persistence
- Generate visualizations (output as text)

**Limitations:**
- No shared state between calls (fresh subprocess each time)
- No filesystem sandboxing — code has the same permissions as the server
- Standard library only unless packages are pre-installed

### `mcp_python_evaluate`

Evaluate a single Python expression.

```
Arguments:
  expression  (string, required)  — Python expression to evaluate
  timeout     (integer, optional) — Max seconds (1–60, default: 10)

Returns: repr() of the result value.
```

**Use cases:**
- Quick math: `2**10 + 5`
- List/dict comprehensions: `[x*2 for x in range(10)]`
- String operations: `"hello".upper()`
- Type checks: `isinstance(42, int)`
- Function calls: `sum(range(100))`

### `mcp_python_packages`

Manage pip packages.

```
Arguments:
  action   (string, required)  — "list", "check", or "install"
  package  (string, optional)  — Package name (required for check/install)

Returns: formatted result string.
```

**Use cases:**
- `action="list"` — See all installed packages
- `action="check", package="numpy"` — Verify a package is available
- `action="install", package="requests"` — Install a new package

## Usage Patterns

### Pattern 1: Execute multi-line code

```python
# Call mcp_python_execute with:
code = '''
import json
data = {"numbers": [1, 2, 3, 4, 5]}
result = sum(data["numbers"])
print(f"Sum: {result}")
print(f"Average: {result / len(data['numbers'])}")
'''
```

### Pattern 2: Evaluate and use the result

```python
# Call mcp_python_evaluate with:
expression = "sorted([3, 1, 4, 1, 5, 9, 2, 6])"
# Returns: [1, 1, 2, 3, 4, 5, 6, 9]
```

### Pattern 3: Install a package then use it

```python
# Step 1: Check if package is installed
mcp_python_packages(action="check", package="pandas")

# Step 2: Install if missing
mcp_python_packages(action="install", package="pandas")

# Step 3: Use it
mcp_python_execute(code="import pandas; print(pandas.__version__)")
```

## Error Handling

- **Syntax errors** — The traceback appears in `[stderr]` section
- **Runtime exceptions** — Traceback in `[stderr]`, exit code non-zero
- **Timeouts** — `[error]` reports the timeout duration
- **Missing packages** — `check` action returns "NOT installed" message
- **Install failures** — pip's stderr is returned verbatim
- **Invalid actions** — `python_packages` returns list of valid actions

## Server Details

- **Language:** Python 3.10+
- **Dependency:** `pip install mcp` (MCP Python SDK)
- **Transport:** stdio (reads stdin, writes stdout)
- **Protocol:** JSON-RPC 2.0 over MCP
- **Process model:** Single process, event loop, stays alive until stdin closes

## Testing

```bash
# Verify server starts
python3 spola-mcp-python-server.py --help

# Test via pipe
echo '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"python_execute","arguments":{"code":"print(1+1)"}}}' | timeout 3 python3 spola-mcp-python-server.py
```
