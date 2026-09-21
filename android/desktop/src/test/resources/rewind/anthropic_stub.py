"""Loopback-only protocol fixture. Stores fake prompts, never header values."""
import http.server
import json
import pathlib
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
                                  "fake_auth": self.headers.get("x-api-key") == key or
                                  self.headers.get("Authorization") == "Bearer " + key}) + "\n")
        if "count_tokens" in self.path:
            self.reply_json({"input_tokens": 1})
            return
        message = {"id": "msg_fixture_" + str(time.time_ns()), "type": "message", "role": "assistant",
                   "model": body.get("model", "fixture"), "content": [{"type": "text", "text": "ok"}],
                   "stop_reason": "end_turn", "stop_sequence": None,
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
        event("content_block_start", {"type": "content_block_start", "index": 0, "content_block": {"type": "text", "text": ""}})
        event("content_block_delta", {"type": "content_block_delta", "index": 0, "delta": {"type": "text_delta", "text": "ok"}})
        event("content_block_stop", {"type": "content_block_stop", "index": 0})
        event("message_delta", {"type": "message_delta", "delta": {"stop_reason": "end_turn", "stop_sequence": None}, "usage": {"output_tokens": 2}})
        event("message_stop", {"type": "message_stop"})


server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
(root / "port").write_text(str(server.server_port))
server.serve_forever()
