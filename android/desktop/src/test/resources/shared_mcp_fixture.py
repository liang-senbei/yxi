#!/usr/bin/env python3
"""yxi-shared-mcp-fixture — minimal stdio MCP test server, Python stdlib only.

Purpose: same-machine cross-runner check. Point Claude Code / Codex / OpenCode
at this fixture and compare the pid inside each ``yxi_echo`` result: the same
pid means the runners share one server process; distinct pids mean each runner
spawned its own copy of the same registered server (the per-runner adapter
model from design/shared-plugin-prd.md).

Surface kept deliberately tiny (NDJSON JSON-RPC 2.0 over stdio):
  initialize, notifications/initialized, tools/list, tools/call (yxi_echo).
Plus ``ping`` -> empty result (spec-required, one line). Everything else:
requests get JSON-RPC -32601, notifications are ignored.

Spec basis (handshake-era revisions; see README for the 2026-07-28 note):
- Version negotiation: 2025-06-18 basic/lifecycle "Version Negotiation" —
  server echoes a supported requested version, else responds with its latest.
- Framing: 2025-06-18 basic/transports (stdio) — newline-delimited, no
  embedded newlines. Batching was removed in 2025-06-18 (changelog #1),
  so array frames are rejected as Invalid Request.
- Unknown tool: server/tools "Error responses" example -> -32602.
- ping: basic/utilities/ping — receiver MUST respond promptly, empty result.

Safety: no filesystem access, no network, no subprocess, no eval. Inputs are
stdin lines only; outputs are JSON-RPC responses (stdout) and diagnostics
(stderr, silent unless YXI_FIXTURE_VERBOSE is set). Nothing is ever logged to
stdout.
"""

import json
import os
import sys
import traceback

SERVER_NAME = "yxi-shared-mcp-fixture"
SERVER_VERSION = "0.1.0"
TOOL_NAME = "yxi_echo"
ECHO_PREFIX = "YXI_SHARED_MCP"
MAX_FRAME = 512 * 1024  # per-frame byte cap, newline included
# Handshake revisions this fixture speaks (see README, "协议版本处理").
SUPPORTED_VERSIONS = ("2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25")

PARSE_ERROR = -32700
INVALID_REQUEST = -32600
METHOD_NOT_FOUND = -32601
INVALID_PARAMS = -32602
INTERNAL_ERROR = -32603
NOT_INITIALIZED = -32002  # implementation range -32000..-32099

VERBOSE = bool(os.environ.get("YXI_FIXTURE_VERBOSE"))

TOOL = {
    "name": TOOL_NAME,
    "description": (
        "Echo test tool: returns the message prefixed with "
        f"{ECHO_PREFIX} and the server pid, so callers can tell which "
        "server process answered."
    ),
    "inputSchema": {
        "type": "object",
        "properties": {
            "message": {"type": "string", "description": "Text to echo back."},
        },
        "required": ["message"],
        "additionalProperties": False,
    },
}


class RpcError(Exception):
    def __init__(self, code, message):
        super().__init__(message)
        self.code = code


def log(message):
    if VERBOSE:
        print(f"[{SERVER_NAME}] {message}", file=sys.stderr, flush=True)


def initialize_result(params):
    if not isinstance(params, dict):
        raise RpcError(INVALID_PARAMS, "initialize params must be an object")
    requested = params.get("protocolVersion")
    if not isinstance(requested, str):
        raise RpcError(INVALID_PARAMS, "protocolVersion must be a string")
    if requested in SUPPORTED_VERSIONS:
        negotiated = requested
    else:
        # Spec: respond with another supported version, latest preferred.
        negotiated = SUPPORTED_VERSIONS[-1]
        log(f"protocolVersion {requested!r} unsupported; negotiating to {negotiated}")
    return {
        "protocolVersion": negotiated,
        "capabilities": {"tools": {"listChanged": False}},
        "serverInfo": {"name": SERVER_NAME, "version": SERVER_VERSION},
        "instructions": (
            f"Test fixture only. Its only tool ({TOOL_NAME}) prefixes results "
            f"with {ECHO_PREFIX} plus the server pid."
        ),
    }


def tool_call_result(params):
    if not isinstance(params, dict):
        raise RpcError(INVALID_PARAMS, "tools/call params must be an object")
    name = params.get("name")
    if name != TOOL_NAME:
        # Spec shape: {"code": -32602, "message": "Unknown tool: invalid_tool_name"}
        raise RpcError(INVALID_PARAMS, f"Unknown tool: {name!r}")
    arguments = params.get("arguments")
    if not isinstance(arguments, dict):
        raise RpcError(INVALID_PARAMS, "arguments must be an object")
    message = arguments.get("message")
    if not isinstance(message, str):
        raise RpcError(INVALID_PARAMS, "arguments.message must be a string")
    return {
        "content": [{"type": "text", "text": f"{ECHO_PREFIX} pid={os.getpid()} {message}"}],
        "isError": False,
    }


def dispatch(method, params, state):
    if method == "initialize":
        return initialize_result(params)
    if method == "ping":
        return {}
    if not state["initialized"]:
        raise RpcError(NOT_INITIALIZED, f"{method} before notifications/initialized")
    if method == "tools/list":
        return {"tools": [TOOL]}
    if method == "tools/call":
        return tool_call_result(params)
    raise RpcError(METHOD_NOT_FOUND, f"Method not found: {method}")


def emit(out, payload):
    """One JSON line to stdout, flushed. Never anything else goes there."""
    line = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
    out.write(line.encode("utf-8") + b"\n")
    out.flush()


def emit_error(out, request_id, code, message, data=None):
    error = {"code": code, "message": message}
    if data is not None:
        error["data"] = data
    emit(out, {"jsonrpc": "2.0", "id": request_id, "error": error})


def read_frames(stream):
    """Yield (bytes_of_line_without_newline, oversized_flag) until EOF.

    A line is complete at b"\\n" or at EOF. Once a frame exceeds MAX_FRAME the
    rest of that line is discarded (bounded memory) and one oversized_flag
    frame is yielded when the line ends. The cap counts the trailing newline.
    """
    while True:
        buf = bytearray()
        oversized = False
        while True:
            piece = stream.readline(MAX_FRAME + 1)
            if not piece:  # EOF
                if buf or oversized:
                    yield bytes(buf), oversized
                return
            if oversized:
                if piece.endswith(b"\n"):
                    yield b"", True
                    break
                continue
            buf += piece
            if len(buf) > MAX_FRAME:
                oversized = True
                buf.clear()
                if piece.endswith(b"\n"):
                    yield b"", True
                    break
                continue
            if piece.endswith(b"\n"):
                yield bytes(buf[:-1]), False
                break
            # Shorter than the cap but no newline yet: keep reading the line.


def handle_frame(line, out, state):
    """Parse one frame and answer it. Notifications get no reply, ever."""
    try:
        message = json.loads(line.decode("utf-8"))
    except (UnicodeDecodeError, ValueError) as exc:
        # JSON-RPC 2.0: id un-detectable on parse errors -> null.
        emit_error(out, None, PARSE_ERROR, "Parse error", str(exc)[:200])
        return
    if isinstance(message, list):
        # Batching was removed from MCP in the 2025-06-18 revision.
        emit_error(out, None, INVALID_REQUEST,
                   "Batch requests are not supported (removed from MCP in 2025-06-18)")
        return
    request_id = message.get("id") if isinstance(message, dict) else None
    if (not isinstance(message, dict)
            or message.get("jsonrpc") != "2.0"
            or not isinstance(message.get("method"), str)):
        emit_error(out, request_id, INVALID_REQUEST, "Invalid Request")
        return
    if "id" not in message:  # notification: never replied to
        if message["method"] == "notifications/initialized":
            state["initialized"] = True
            log("client finished initialization")
        else:
            log(f"ignored notification {message['method']!r}")
        return
    # Preserve the id exactly as received (string/number/null pass through).
    try:
        result = dispatch(message["method"], message.get("params"), state)
    except RpcError as exc:
        emit_error(out, request_id, exc.code, str(exc))
    except Exception:
        log(traceback.format_exc())
        emit_error(out, request_id, INTERNAL_ERROR, "Internal error")
    else:
        emit(out, {"jsonrpc": "2.0", "id": request_id, "result": result})


def main():
    state = {"initialized": False}
    log(f"starting pid={os.getpid()} versions={','.join(SUPPORTED_VERSIONS)}")
    for line, oversized in read_frames(sys.stdin.buffer):
        if oversized:
            # Payload was discarded, so the id is undetectable -> null per JSON-RPC 2.0.
            emit_error(sys.stdout.buffer, None, INVALID_REQUEST,
                       f"frame exceeds {MAX_FRAME}-byte limit")
            continue
        if line:
            handle_frame(line, sys.stdout.buffer, state)


if __name__ == "__main__":
    try:
        main()
    except (BrokenPipeError, KeyboardInterrupt):
        pass  # runner closed the pipe or sent SIGINT: a normal fixture exit
    sys.exit(0)
