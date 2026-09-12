"""Trusted, loopback-only WebSocket page for desktop browser visual verification."""
import base64
import functools
import hashlib
import http.server
from pathlib import Path
import struct
import sys
import time

root = Path(sys.argv[1])
(root / 'browser-live.txt').write_text('Before the edit')
(root / 'index.html').write_text('''<!doctype html><meta charset="utf-8"><title>Preview lab</title>
<style>body{margin:0;padding:24px;font:15px/1.6 Arial,sans-serif;color:#202536;background:#fff}small{font-size:11px;letter-spacing:2px;color:#6c7285}h1{font-size:28px;line-height:36px;margin:12px 0}p{color:#727789;margin:0 0 20px}.card{background:#f2f3fa;border-radius:16px;padding:20px}button{border:0;border-radius:10px;background:#535ada;color:white;padding:12px 18px;font:inherit}input{display:block;margin-top:20px}</style>
<small>REMOTE DEVELOPMENT</small><h1 id="headline">Before the edit</h1><p>A live page, beside your conversation.</p>
<div class="card"><b>Make the next change together.</b><p>Choose an element and describe the result you want.</p><button id="action">Primary action</button></div>
<input type="password" value="DO_NOT_CAPTURE" aria-label="Sensitive fixture field">
<script>const live=new WebSocket(location.origin.replace('http','ws')+'/live');live.onmessage=e=>document.getElementById('headline').textContent=e.data;</script>''')

class Handler(http.server.SimpleHTTPRequestHandler):
    def do_GET(self):
        if self.headers.get('Upgrade', '').lower() != 'websocket':
            return super().do_GET()
        accept = base64.b64encode(hashlib.sha1((self.headers['Sec-WebSocket-Key'] + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').encode()).digest()).decode()
        self.send_response(101)
        self.send_header('Upgrade', 'websocket')
        self.send_header('Connection', 'Upgrade')
        self.send_header('Sec-WebSocket-Accept', accept)
        self.end_headers()
        previous = None
        try:
            for _ in range(1200):
                data = (root / 'browser-live.txt').read_bytes()
                if data != previous:
                    header = bytes([0x81, len(data)]) if len(data) < 126 else b'\x81\x7e' + struct.pack('!H', len(data))
                    self.connection.sendall(header + data)
                    previous = data
                time.sleep(.1)
        except (BrokenPipeError, ConnectionResetError):
            pass

server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), functools.partial(Handler, directory=str(root)))
(root / 'browser-port').write_text(str(server.server_port))
server.serve_forever()
