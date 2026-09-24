"""Loopback-only protocol fixture. Stores fake prompts, never header values."""
import http.server
import json
import pathlib
import re
import shlex
import sys
import threading
import time

root = pathlib.Path(sys.argv[1])
lock = threading.Lock()
key = "sk-ant-yxi-container-test-only"


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def reply_json(self, value, status=200):
        data = json.dumps(value).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        self.reply_json({"ok": True})

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        if length > 2 * 1024 * 1024:
            self.reply_json({"error": "fixture body limit"}, 413)
            return
        body = json.loads(self.rfile.read(length) or b"{}")
        with lock, (root / "requests.jsonl").open("a") as log:
            log.write(json.dumps({"path": self.path, "messages": body.get("messages", []),
                                  "fixture_api_header": self.headers.get("x-api-key") == key,
                                  "fixture_oauth_header": self.headers.get("Authorization") == "Bearer synthetic-oauth-request-token",
                                  "fake_auth": self.headers.get("x-api-key") == key or
                                  self.headers.get("Authorization") == "Bearer " + key}) + "\n")
        if "count_tokens" in self.path:
            self.reply_json({"input_tokens": 1})
            return
        marker = next((found for m in reversed(body.get("messages", [])) if m.get("role") == "user"
                       for found in [re.search(r"(?:KEEP|FOLLOWUP|DROP|EDIT|VERIFY|SINGLE|INTERACTIVE|APP|SECOND|RECOVERY|ROOT)-[A-Za-z-]+", json.dumps(m.get("content", "")))] if found), None)
        answer = "answer:" + marker.group(0) if marker else "ok"
        if marker and marker.group(0) == "ROOT-container-native-stop":
            (root / "native-stop-request-started").write_text("ready")
            time.sleep(15)  # Give the real interactive CLI a deterministic cancellation window.
        tool = None
        if marker and marker.group(0) in ("KEEP-container-first", "DROP-container-third", "KEEP-control-permission", "FOLLOWUP-control-permission"):
            tool_id = "tool_fixture_" + marker.group(0).replace("-", "_")
            completed = any(block.get("type") == "tool_result" and block.get("tool_use_id") == tool_id
                            for m in body.get("messages", []) if isinstance(m.get("content"), list)
                            for block in m["content"] if isinstance(block, dict))
            if not completed:
                if marker.group(0).endswith("control-permission"):
                    target = root / ("approved-control.txt" if marker.group(0).startswith("KEEP") else "denied-control.txt")
                    tool = {"type": "tool_use", "id": tool_id, "name": "Bash", "input": {"command": "printf allowed > " + shlex.quote(str(target)), "description": "Fixture permission write"}}
                else:
                    name = "retained-tool.txt" if marker.group(0).startswith("KEEP") else "discarded-tool.txt"
                    tool = {"type": "tool_use", "id": tool_id, "name": "Read", "input": {"file_path": str(root / "project" / name)}}
        message = {"id": "msg_fixture_" + str(time.time_ns()), "type": "message", "role": "assistant",
                   "model": body.get("model", "fixture"), "content": [tool] if tool else [{"type": "text", "text": answer}],
                   "stop_reason": "tool_use" if tool else "end_turn", "stop_sequence": None,
                   "usage": {"input_tokens": 1, "output_tokens": 2}}
        if not body.get("stream"):
            self.reply_json(message)
            return
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.end_headers()

        def event(kind, data):
            self.wfile.write(("event: " + kind + "\ndata: " + json.dumps(data) + "\n\n").encode())
            self.wfile.flush()

        event("message_start", {"type": "message_start", "message": dict(message, content=[], stop_reason=None)})
        event("content_block_start", {"type": "content_block_start", "index": 0, "content_block": dict(tool, input={}) if tool else {"type": "text", "text": ""}})
        delta = {"type": "input_json_delta", "partial_json": json.dumps(tool["input"])} if tool else {"type": "text_delta", "text": answer}
        event("content_block_delta", {"type": "content_block_delta", "index": 0, "delta": delta})
        event("content_block_stop", {"type": "content_block_stop", "index": 0})
        event("message_delta", {"type": "message_delta", "delta": {"stop_reason": message["stop_reason"], "stop_sequence": None}, "usage": {"output_tokens": 2}})
        event("message_stop", {"type": "message_stop"})


server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
(root / "port").write_text(str(server.server_port))
server.serve_forever()
