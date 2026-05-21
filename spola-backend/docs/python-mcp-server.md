# Spola Python MCP Companion Server

A lightweight MCP (Model Context Protocol) server that exposes Python execution as
MCP tools. Designed to connect to **Spola's MCP Client** or any other MCP-aware
application (Claude Desktop, Cursor, etc.).

## Overview

The Python MCP Companion Server provides three tools that let AI agents execute
Python code, evaluate expressions, and manage packages — all through the standard
MCP stdio transport.

### Tools

| Tool | Description |
|------|-------------|
| `python_execute` | Execute arbitrary Python code in a subprocess. Returns stdout, stderr, exit code. |
| `python_evaluate` | Evaluate a single Python expression and return its `repr()` result. |
| `python_packages` | Manage pip packages: list installed, check if installed, or install new ones. |

## Requirements

- Python 3.10+
- `mcp` Python SDK (`pip install mcp`)

## Installation

```bash
pip install mcp
```

Make the server script executable:

```bash
chmod +x spola-mcp-python-server.py
```

Verify it starts correctly:

```bash
python3 /path/to/spola-mcp-python-server.py --help
```

## Connecting from Spola

Add the Python server to Spola's MCP client configuration at `~/.spola/mcp-servers.json`:

```json
[
  {
    "name": "python",
    "transport": "stdio",
    "command": "python3",
    "args": ["/path/to/spola-mcp-python-server.py"],
    "enabled": true
  }
]
```

Restart Spola. The tools will be registered with the namespaced prefix `mcp_python_`:

- `mcp_python_execute`
- `mcp_python_evaluate`
- `mcp_python_packages`

## Connecting from Other MCP Clients

### Claude Desktop

Add to `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "python": {
      "command": "python3",
      "args": ["/path/to/spola-mcp-python-server.py"]
    }
  }
}
```

### Cursor

In Cursor's MCP server settings:

| Field | Value |
|-------|-------|
| Name | Python |
| Type | stdio |
| Command | `python3` |
| Arguments | `/path/to/spola-mcp-python-server.py` |

## Tool Reference

### `python_execute`

Execute Python code in a fresh subprocess.

**Parameters:**
- `code` (string, **required**) — Python source code to execute.
- `timeout` (integer, optional, default: `30`) — Max seconds before killing the subprocess. Range: 1–300.

**Returns:** Formatted string with `[stdout]`, `[stderr]`, `[error]`, and `[exit_code]` sections.

**Example:**
```
Input:  python_execute(code="print('hello world')")
Output: [stdout]
        hello world

        [exit_code] 0
```

**Edge cases handled:**
- **Syntax error:** Code with syntax errors returns the traceback in `[stderr]` and a non-zero exit code.
- **Runtime exception:** Raised exceptions appear in both `[stderr]` and the traceback.
- **Timeout:** Subprocess killed after `timeout` seconds; `[error]` reports the timeout.
- **Empty code:** `python3 -c ""` returns successfully with empty output.
- **Infinite loop:** Caught by the timeout mechanism.
- **Missing interpreter:** Returns an error if `sys.executable` is not found.

### `python_evaluate`

Evaluate a single Python expression and return the `repr()` of the result.

**Parameters:**
- `expression` (string, **required**) — A single Python expression to evaluate.
- `timeout` (integer, optional, default: `10`) — Max seconds. Range: 1–60.

**Returns:** Formatted string with the repr'd result in `[stdout]`.

**Example:**
```
Input:  python_evaluate(expression="[x**2 for x in range(5)]")
Output: [stdout]
        [0, 1, 4, 9, 16]

        [exit_code] 0
```

**Edge cases handled:**
- **Expression error:** Invalid expressions return traceback in `[stderr]`.
- **Side effects:** The expression is run in a subprocess — no shared state.
- **Complex objects:** `repr()` is used, so custom `__repr__` methods work.

### `python_packages`

Manage Python packages via pip.

**Parameters:**
- `action` (string, **required**) — One of: `"list"`, `"check"`, `"install"`.
- `package` (string, optional) — Package name (required for `"check"` and `"install"`).

**Returns:** Formatted result string.

**Examples:**
```
Input:  python_packages(action="list")
Output: Installed packages (42):
          - certifi == 2024.12.14
          - charset-normalizer == 3.4.1
          - ...

Input:  python_packages(action="check", package="flask")
Output: Package 'flask' is NOT installed.

Input:  python_packages(action="install", package="requests")
Output: Successfully installed 'requests'.
```

**Edge cases handled:**
- **Invalid action:** Returns an error listing valid actions.
- **Missing package (check):** Clearly reports "NOT installed" vs. "is installed".
- **Install failure:** Returns pip's stderr output on failure.
- **pip timeout:** `list` times out after 30s, `check` after 15s, `install` after 120s.
- **Empty results:** `list` on a fresh environment returns `Installed packages (0):`.

## Protocol

The server uses MCP stdio transport: it reads JSON-RPC 2.0 messages from stdin and
writes responses to stdout. Stderr is reserved for logging and diagnostics.

The server stays alive until stdin is closed. Each message is a single line of JSON
followed by a newline. Responses follow the same format.

### Supported MCP Methods

| Method | Purpose |
|--------|---------|
| `initialize` | Protocol handshake |
| `tools/list` | List available tools |
| `tools/call` | Call a tool by name with arguments |
| `ping` | Health check |
| `notifications/initialized` | Client ready signal |
| `notifications/tools/list_changed` | Tool list change notification (not currently sent) |

## Security

- Code executes in a **fresh subprocess** each time — no shared state between calls.
- No filesystem sandboxing is applied. The subprocess has the same permissions as the user running the server.
- **Use with trusted code only.** This server does not restrict imports or system calls.
- Consider running the server in a restricted environment (container, virtualenv, etc.) for production use.

## Testing

### Quick test via pipes

```bash
# Test initialization
echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"0.1.0","capabilities":{},"clientInfo":{"name":"test","version":"1.0.0"}}}' | timeout 3 python3 spola-mcp-python-server.py 2>&1

# Test python_execute
echo '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"python_execute","arguments":{"code":"print(1+1)"}}}' | timeout 3 python3 spola-mcp-python-server.py 2>&1

# Test python_evaluate
echo '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"python_evaluate","arguments":{"expression":"42 * 2"}}}' | timeout 3 python3 spola-mcp-python-server.py 2>&1
```

### Integration test with Spola

Use Spola's test script:

```bash
python3 scripts/test_mcp_stdio.py
```

## Architecture

```
Spola Agent / MCP Client
       │
       ▼  stdin/stdout (JSON-RPC)
┌─────────────────────────────┐
│  FastMCP Server             │
│  ┌─────────────────────┐    │
│  │  python_execute     │────│───▶ python3 -c <code>
│  ├─────────────────────┤    │
│  │  python_evaluate    │────│───▶ python3 -c "print(repr(...))"
│  ├─────────────────────┤    │
│  │  python_packages    │────│───▶ pip list/show/install
│  └─────────────────────┘    │
└─────────────────────────────┘
```

## Troubleshooting

**Server doesn't start:**
- Ensure `mcp` package is installed: `pip install mcp`
- Check Python version: `python3 --version` (needs 3.10+)
- Test with `--help` flag: `python3 spola-mcp-python-server.py --help`

**No tools visible in Spola:**
- Verify `~/.spola/mcp-servers.json` has correct path
- Ensure `"enabled": true`
- Check Spola logs for connection errors
- Run `spola mcp list` to see configured servers

**Tool calls fail:**
- Check that the Python interpreter at `sys.executable` is accessible
- Verify pip works: `python3 -m pip list`
- Check timeout values — complex code may need more time
