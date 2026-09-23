"""Check a Yxi-owned task MCP file and native name resolution without printing configuration."""
import hashlib
import json
import os
import pathlib
import re
import select
import signal
import stat
import subprocess
import sys
import time


def main():
    path, expected, binary, directory = sys.argv[1:]
    location = pathlib.Path(path)
    parent = pathlib.Path.home() / '.yxi' / 'shared-mcp'
    if location.parent != parent or not re.fullmatch(r'[a-f0-9]{32}\.json', location.name):
        return 'mcp-config-invalid'
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(fd, 'rb') as source:
        info = os.fstat(source.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077:
            return 'mcp-config-invalid'
        raw = source.read(2 * 1024 * 1024 + 1)
    if len(raw) > 2 * 1024 * 1024 or hashlib.sha256(raw).hexdigest() != expected:
        return 'mcp-config-changed'
    servers = json.loads(raw)['mcpServers']
    if not isinstance(servers, dict) or not servers:
        return 'mcp-config-invalid'
    deadline = time.monotonic() + 30
    for name in servers:
        if not re.fullmatch(r'[A-Za-z0-9_-]{1,64}', name):
            return 'mcp-config-invalid'
        env = os.environ.copy()
        env.update(NO_COLOR='1', DISABLE_AUTOUPDATER='1')
        process = subprocess.Popen([binary, 'mcp', 'get', name], cwd=directory, env=env,
                                   stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                   start_new_session=True)
        try:
            output = bytearray()
            limit = min(deadline, time.monotonic() + 10)
            while True:
                if time.monotonic() > limit:
                    return 'mcp-check-failed'
                if not select.select([process.stdout], [], [], 0.1)[0]:
                    continue
                chunk = os.read(process.stdout.fileno(), 4096)
                if not chunk:
                    break
                output.extend(chunk)
                if len(output) > 16384:
                    return 'mcp-check-failed'
            process.wait(timeout=2)
            if process.returncode == 0:
                return 'mcp-conflict'
            text = re.sub(r'\x1b\[[0-9;]*m', '', output.decode('utf-8', errors='replace'))
            lines = [line.strip().removeprefix('Error: ') for line in text.splitlines()]
            if not any(line == f'No MCP server found with name: {name}' or line.startswith(f'No MCP server named "{name}".') for line in lines):
                return 'mcp-check-failed'
        finally:
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()
            process.stdout.close()
    return 'ready'


try:
    print(main())
except Exception:
    print('mcp-check-failed')
