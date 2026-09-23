"""Local Chat Completions fixture; confirmation requires a native MCP tool response."""
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
        completed = any(m.get("role") == "tool" and "YXI_SHARED_MCP" in json.dumps(m.get("content")) for m in body.get("messages", []))
        tool = next((item.get("function", {}).get("name") for item in body.get("tools", []) if item.get("function", {}).get("name", "").endswith("yxi_echo")), None)
        with (root / "calls.jsonl").open("a") as log:
            log.write(json.dumps({"tool": tool, "result_confirmed": completed}) + "\n")
        answer = "SHARED_MCP_NATIVE_CONFIRMED" if completed else "Fixture title"
        tool_call = {"index": 0, "id": "shared_mcp_call", "type": "function", "function": {"name": tool, "arguments": json.dumps({"message": "opencode-native-call"})}} if tool and not completed else None
        if body.get("stream"):
            def chunk(delta, finish=None):
                return {"id": "chatcmpl_shared", "object": "chat.completion.chunk", "created": 1, "model": body.get("model", "fixture-model"),
                        "choices": [{"index": 0, "delta": delta, "finish_reason": finish}]}
            delta = {"tool_calls": [tool_call]} if tool_call else {"content": answer}
            output = "".join("data: " + json.dumps(item) + "\n\n" for item in [chunk({"role": "assistant"}), chunk(delta), chunk({}, "tool_calls" if tool_call else "stop")]) + "data: [DONE]\n\n"
            content_type = "text/event-stream"
        else:
            output = json.dumps({"id": "chatcmpl_shared", "object": "chat.completion", "created": 1, "model": body.get("model", "fixture-model"),
                                 "choices": [{"index": 0, "message": {"role": "assistant", "content": answer}, "finish_reason": "stop"}],
                                 "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}})
            content_type = "application/json"
        data = output.encode()
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
(root / "port").write_text(str(server.server_port))
server.serve_forever()
