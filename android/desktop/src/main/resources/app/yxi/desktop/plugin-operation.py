import base64
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import tempfile

def private_directory(path):
    path.mkdir(mode=0o700, exist_ok=True)
    info = path.lstat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077:
        raise ValueError('unsafe storage')

def read_file(path):
    if path.is_symlink():
        raise ValueError('linked configuration')
    try:
        with path.open('rb') as stream:
            data = stream.read(4194305)
        if len(data) > 4194304:
            raise ValueError('large configuration')
        return data
    except FileNotFoundError:
        return None

def write_file(path, data):
    fd, name = tempfile.mkstemp(prefix='.pending-', dir=path.parent)
    try:
        with os.fdopen(fd, 'wb') as stream:
            stream.write(data); stream.flush(); os.fsync(stream.fileno())
        os.replace(name, path)
        fd = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
        try: os.fsync(fd)
        finally: os.close(fd)
    finally:
        if os.path.exists(name): os.unlink(name)

def execute(request, home=None, run=subprocess.run):
    home = Path(home or os.path.expanduser('~')).resolve()
    base = home / '.claude'
    if Path(os.environ.get('CLAUDE_CONFIG_DIR', str(base))).resolve() != base:
        return {'state': 'unsupported-config-home'}
    action = request.get('action')
    operation = request.get('operation', '')
    if action not in ('prepare', 'set', 'status') or not re.fullmatch(r'[a-f0-9]{32}', operation):
        return {'state': 'invalid'}
    store = home / '.yxi'
    private_directory(store)
    store = store / 'plugin-operations'
    private_directory(store)
    record = store / (operation + '.json')
    lockfd = os.open(store / 'lock', os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    try:
        try: fcntl.flock(lockfd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError: return {'state': 'busy'}
        previous = read_file(record)
        if action == 'status':
            if previous is None: return {'state': 'missing'}
            result = json.loads(previous)
            if result['state'] == 'started': result['state'] = 'unknown'
            return result
        plugin = request.get('plugin', '')
        scope = request.get('scope')
        directory = request.get('directory', '')
        if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.@+-]{0,255}', plugin) or scope not in ('user', 'project', 'local'):
            return {'state': 'invalid'}
        cwd = home if scope == 'user' else Path(directory)
        if not cwd.is_absolute() or not cwd.is_dir() or str(cwd.resolve()) != str(cwd):
            return {'state': 'invalid-directory'}
        config = base / 'settings.json' if scope == 'user' else cwd / '.claude' / ('settings.local.json' if scope == 'local' else 'settings.json')
        if config.parent.is_symlink() or base.is_symlink():
            return {'state': 'unsupported-linked-config'}
        def snapshot():
            raw = read_file(config)
            registry = read_file(base / 'plugins' / 'installed_plugins.json')
            parsed = json.loads(raw) if raw is not None else {}
            installed = json.loads(registry) if registry is not None else {}
            if not isinstance(parsed, dict) or not isinstance(parsed.get('enabledPlugins', {}), dict):
                raise ValueError('invalid settings')
            entries = installed.get('plugins', {}).get(plugin, [])
            matches = [p for p in entries if p.get('scope') == scope and (scope == 'user' or p.get('projectPath') == str(cwd))]
            if len(matches) != 1: raise ValueError('installation not unique')
            digest = hashlib.sha256(json.dumps([None if raw is None else base64.b64encode(raw).decode(), None if registry is None else base64.b64encode(registry).decode()]).encode()).hexdigest()
            return raw, parsed, digest
        desired = request.get('enabled')
        if action == 'set' and type(desired) is not bool:
            return {'state': 'invalid'}
        identity = dict(plugin=plugin, scope=scope, directory=str(cwd), enabled=desired)
        if previous is not None:
            old = json.loads(previous)
            if old.get('target') != identity: return {'state': 'operation-conflict'}
            if old['state'] == 'started': old['state'] = 'unknown'
            return old
        raw, settings, digest = snapshot()
        if action == 'prepare':
            value = settings.get('enabledPlugins', {}).get(plugin)
            return dict(state='prepared', fingerprint=digest, path=str(config), enabled=value if type(value) is bool else None)
        if request.get('fingerprint') != digest:
            return {'state': 'changed'}
        # No execution until the prior config and durable intent have both been saved.
        backup = store / (operation + '.before')
        write_file(backup, raw if raw is not None else b'')
        result = dict(state='started', operation=operation, target=identity, existed=raw is not None, before=digest)
        write_file(record, json.dumps(result).encode())
        try:
            with tempfile.TemporaryFile() as out, tempfile.TemporaryFile() as err:
                completed = run(['claude', 'plugin', 'enable' if desired else 'disable', plugin, '--scope', scope], cwd=str(cwd), stdin=subprocess.DEVNULL, stdout=out, stderr=err, timeout=20)
            _, after, after_digest = snapshot()
            result['state'] = 'configured' if completed.returncode == 0 and after.get('enabledPlugins', {}).get(plugin) is desired else 'unknown'
            result['after'] = after_digest
        except Exception:
            result['state'] = 'unknown'
        write_file(record, json.dumps(result).encode())
        return result
    finally:
        os.close(lockfd)

if __name__ == '__main__':
    try: result = execute(json.loads(base64.b64decode(sys.argv[1], validate=True)))
    except Exception: result = {'state': 'unconfirmed'}
    print('__YXI_PLUGIN_OP__:' + json.dumps(result))
