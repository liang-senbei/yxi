"""Loopback fixture; SSE shapes follow official openai/codex rust-v0.153.4 test events.
See codex-rs/core/tests/common/responses.rs. No real credentials or external network.
"""
import ctypes
import gzip
import http.server
import json
import pathlib
import sys
import threading
import time

root = pathlib.Path(sys.argv[1])
key = sys.argv[2]
lock = threading.Lock()


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        authenticated = self.headers.get('Authorization') == 'Bearer ' + key
        with lock, (root / 'models-requests.jsonl').open('a') as out:
            out.write(json.dumps({'path': self.path, 'authenticated': authenticated}) + '\n')
        if not authenticated:
            self.send_error(401)
            return
        index = key.rsplit('-', 1)[-1]
        raw = json.dumps({'data': [{'id': 'fixture-model-' + index}, {'id': 'provider/alternative:' + index}]}).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_POST(self):
        raw = self.rfile.read(int(self.headers.get('Content-Length', '0')))
        encoding = self.headers.get('Content-Encoding', '')
        if encoding == 'gzip':
            raw = gzip.decompress(raw)
        elif encoding == 'zstd':
            zstd = ctypes.CDLL('libzstd.so.1')
            zstd.ZSTD_decompress.argtypes = [ctypes.c_void_p, ctypes.c_size_t, ctypes.c_void_p, ctypes.c_size_t]
            zstd.ZSTD_decompress.restype = ctypes.c_size_t
            buffer = ctypes.create_string_buffer(4 * 1024 * 1024)
            count = zstd.ZSTD_decompress(buffer, len(buffer), raw, len(raw))
            if count > len(buffer):
                raise ValueError('Invalid fixture compression')
            raw = buffer.raw[:count]
        body = json.loads(raw)
        authenticated = self.headers.get('Authorization') == 'Bearer ' + key
        with lock, (root / 'requests.jsonl').open('a') as out:
            out.write(json.dumps({'path': self.path, 'input': body.get('input'), 'model': body.get('model'), 'tools': body.get('tools'),
                                 'authenticated': authenticated}) + '\n')
        if not authenticated:
            self.send_error(401)
            return
        response_id = 'resp_fixture_' + str(time.time_ns())
        completed = any(item.get('type') == 'function_call_output' and 'YXI_SHARED_MCP' in json.dumps(item.get('output'))
                        for item in body.get('input', []) if isinstance(item, dict))
        if not completed and any(item.get('type') == 'function_call_output' for item in body.get('input', []) if isinstance(item, dict)):
            self.send_error(400, 'Native MCP tool did not return the expected marker; fixture will not retry')
            return
        def find_tool(items, namespace=None):
            for item in items:
                if item.get('type') == 'function' and item.get('name', '').endswith('yxi_echo'):
                    return item['name'], namespace
                if item.get('type') == 'namespace':
                    found = find_tool(item.get('tools', []), item.get('name'))
                    if found:
                        return found
            return None
        tool = find_tool(body.get('tools', []))
        if not completed and tool is None:
            self.send_error(400, 'Fixture did not receive an advertised yxi_echo MCP tool')
            return
        if completed:
            item = {'type': 'message', 'role': 'assistant', 'id': 'msg_' + response_id,
                    'content': [{'type': 'output_text', 'text': 'SHARED_MCP_NATIVE_CONFIRMED'}]}
        else:
            item = {'type': 'function_call', 'call_id': 'shared_mcp_call', 'name': tool[0],
                    'arguments': json.dumps({'message': 'codex-native-call'})}
            if tool[1]:
                item['namespace'] = tool[1]
        events = [
            {'type': 'response.created', 'response': {'id': response_id}},
            {'type': 'response.output_item.done', 'item': item},
            {'type': 'response.completed', 'response': {'id': response_id, 'usage': {
                'input_tokens': 1, 'input_tokens_details': None, 'output_tokens': 1,
                'output_tokens_details': None, 'total_tokens': 2}}},
        ]
        output = ''.join('event: ' + event['type'] + '\ndata: ' + json.dumps(event) + '\n\n' for event in events).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'text/event-stream')
        self.send_header('Content-Length', str(len(output)))
        self.end_headers()
        self.wfile.write(output)


server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
(root / 'port').write_text(str(server.server_port))
server.serve_forever()
