"""Local Anthropic fixture that requires a real MCP tool result before confirming success."""
import http.server
import json
import pathlib
import sys

root = pathlib.Path(sys.argv[1])


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        if length > 4 * 1024 * 1024:
            self.send_error(413)
            return
        body = json.loads(self.rfile.read(length) or b"{}")
        if "count_tokens" in self.path:
            data = b'{"input_tokens": 1}'
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return
        completed = any(
            block.get("type") == "tool_result" and "YXI_SHARED_MCP" in json.dumps(block.get("content"))
            for message in body.get("messages", []) if isinstance(message.get("content"), list)
            for block in message["content"] if isinstance(block, dict)
        )
        tool = next((item["name"] for item in body.get("tools", []) if item.get("name", "").endswith("yxi_echo")), None)
        with (root / "calls.jsonl").open("a") as log:
            log.write(json.dumps({"tool": tool, "result_confirmed": completed}) + "\n")
        content = {"type": "tool_use", "id": "shared_mcp_call", "name": tool, "input": {"message": "claude-native-call"}} if tool and not completed else None
        answer = "SHARED_MCP_NATIVE_CONFIRMED" if completed else "FIXTURE_ERROR_NO_MCP_TOOL"
        message = {"id": "msg_shared_fixture", "type": "message", "role": "assistant", "model": body.get("model", "fixture"),
                   "content": [], "stop_reason": None, "stop_sequence": None, "usage": {"input_tokens": 1, "output_tokens": 1}}
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.end_headers()

        def event(kind, data):
            self.wfile.write(("event: " + kind + "\ndata: " + json.dumps(data) + "\n\n").encode())
            self.wfile.flush()

        event("message_start", {"type": "message_start", "message": message})
        event("content_block_start", {"type": "content_block_start", "index": 0, "content_block": dict(content, input={}) if content else {"type": "text", "text": ""}})
        delta = {"type": "input_json_delta", "partial_json": json.dumps(content["input"])} if content else {"type": "text_delta", "text": answer}
        event("content_block_delta", {"type": "content_block_delta", "index": 0, "delta": delta})
        event("content_block_stop", {"type": "content_block_stop", "index": 0})
        event("message_delta", {"type": "message_delta", "delta": {"stop_reason": "tool_use" if content else "end_turn"}, "usage": {"output_tokens": 1}})
        event("message_stop", {"type": "message_stop"})


server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
(root / "port").write_text(str(server.server_port))
server.serve_forever()
