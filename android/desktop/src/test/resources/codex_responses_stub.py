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
            out.write(json.dumps({'path': self.path, 'input': body.get('input'), 'model': body.get('model'),
                                 'authenticated': authenticated}) + '\n')
        if not authenticated:
            self.send_error(401)
            return
        response_id = 'resp_fixture_' + str(time.time_ns())
        events = [
            {'type': 'response.created', 'response': {'id': response_id}},
            {'type': 'response.output_item.done', 'item': {'type': 'message', 'role': 'assistant', 'id': 'msg_' + response_id,
                'content': [{'type': 'output_text', 'text': 'fixture-complete'}]}},
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
