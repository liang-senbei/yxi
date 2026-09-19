import base64
import fcntl
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import subprocess
import sys
import tempfile
import tarfile

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

def fingerprint(raw, registry):
    return hashlib.sha256(json.dumps([None if raw is None else base64.b64encode(raw).decode(), None if registry is None else base64.b64encode(registry).decode()]).encode()).hexdigest()

def package_backup(path, target):
    path = Path(path)
    if not path.is_absolute() or not path.is_dir() or path.is_symlink():
        raise ValueError('unavailable plugin directory')
    total = 0
    count = 0
    def checked(info):
        nonlocal total, count
        if not (info.isfile() or info.isdir() or info.issym() or info.islnk()):
            raise ValueError('unsupported plugin file')
        total += info.size; count += 1
        if total > 128 * 1024 * 1024 or count > 20000:
            raise ValueError('plugin backup too large')
        return info
    fd, temporary = tempfile.mkstemp(prefix='.package-', dir=target.parent)
    os.close(fd)
    try:
        with tarfile.open(temporary, 'w', dereference=False) as archive:
            archive.add(path, arcname='plugin', filter=checked)
        with open(temporary, 'rb') as stream:
            hasher = hashlib.sha256()
            for block in iter(lambda: stream.read(1024 * 1024), b''): hasher.update(block)
            digest = hasher.hexdigest()
            os.fsync(stream.fileno())
        os.replace(temporary, target)
        parent = os.open(target.parent, os.O_RDONLY | os.O_DIRECTORY)
        try: os.fsync(parent)
        finally: os.close(parent)
        return digest
    finally:
        if os.path.exists(temporary): os.unlink(temporary)

def unpack_package(archive_path, destination, expected_hash):
    fd = os.open(archive_path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(fd, 'rb') as stream:
        if os.fstat(stream.fileno()).st_size > 160 * 1024 * 1024:
            raise ValueError('large archive')
        digest = hashlib.sha256()
        for block in iter(lambda: stream.read(1024 * 1024), b''): digest.update(block)
        if digest.hexdigest() != expected_hash: raise ValueError('changed archive')
        stream.seek(0)
        with tarfile.open(fileobj=stream, mode='r:') as archive:
            members = archive.getmembers()
            if len(members) > 20000 or sum(p.size for p in members) > 128 * 1024 * 1024:
                raise ValueError('large archive')
            names = set()
            symbolic = {str(PurePosixPath(m.name)) for m in members if m.issym()}
            if not any(m.name == 'plugin' and m.isdir() for m in members): raise ValueError('missing root')
            for member in members:
                path = PurePosixPath(member.name)
                if path.is_absolute() or '..' in path.parts or not path.parts or path.parts[0] != 'plugin' or '\\' in member.name or str(path) in names:
                    raise ValueError('invalid archive path')
                if not (member.isdir() or member.isfile() or member.issym() or member.islnk()):
                    raise ValueError('invalid archive member')
                if any(str(parent) in symbolic for parent in path.parents): raise ValueError('linked archive parent')
                names.add(str(path))
            destination.mkdir(mode=0o700) # A fresh location, never an existing cache directory.
            regular = {m.name for m in members if m.isfile()}
            # Materialize ordinary files first; archive links can never redirect writes.
            for member in members:
                target = destination / member.name
                if member.isdir(): target.mkdir(mode=0o700, parents=True, exist_ok=True)
                elif member.isfile():
                    target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
                    with archive.extractfile(member) as source, target.open('xb') as output:
                        for block in iter(lambda: source.read(1024 * 1024), b''): output.write(block)
                        output.flush(); os.fsync(output.fileno())
                    target.chmod(member.mode & 0o777)
            for member in members:
                if member.islnk():
                    if member.linkname not in regular: raise ValueError('invalid hard link')
                    target = destination / member.name
                    target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
                    os.link(destination / member.linkname, target)
            for member in members:
                if member.issym():
                    target = destination / member.name
                    target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
                    os.symlink(member.linkname, target) # Preserve links without following them.
            for directory, _, _ in os.walk(destination, followlinks=False):
                directory_fd = os.open(directory, os.O_RDONLY | os.O_DIRECTORY)
                try: os.fsync(directory_fd)
                finally: os.close(directory_fd)
    return destination / 'plugin'

def rollback_package(request, home, store, record, previous):
    source_id = request.get('restores', '')
    if not re.fullmatch(r'[a-f0-9]{32}', source_id) or source_id == request['operation']:
        return {'state': 'invalid'}
    identity = dict(kind='rollback', restores=source_id)
    if previous is not None:
        result = json.loads(previous)
        if result.get('target') != identity: return {'state': 'operation-conflict'}
        if result['state'] == 'started': result['state'] = 'unknown'
        return result
    source_bytes = read_file(store / (source_id + '.json'))
    source = json.loads(source_bytes) if source_bytes else {}
    if source.get('state') not in ('updated', 'uninstalled') or not source.get('packageHash'):
        return {'state': 'restore-unavailable'}
    target = source['target']; scope = target['scope']; plugin = target['plugin']
    if scope not in ('user', 'project', 'local'): return {'state': 'invalid'}
    cwd = home if scope == 'user' else Path(target['directory'])
    if not cwd.is_absolute() or not cwd.is_dir() or cwd.resolve() != cwd: return {'state': 'invalid-directory'}
    config = home / '.claude/settings.json' if scope == 'user' else cwd / '.claude' / ('settings.local.json' if scope == 'local' else 'settings.json')
    if config.parent.is_symlink(): return {'state': 'unsupported-linked-config'}
    registry_path = home / '.claude/plugins/installed_plugins.json'
    if (home / '.claude').is_symlink() or registry_path.parent.is_symlink(): return {'state': 'unsupported-linked-config'}
    before = read_file(store / (source_id + '.before'))
    old_registry = read_file(store / (source_id + '.registry-before'))
    if before is None or old_registry is None or fingerprint(before if source['existed'] else None, old_registry) != source['before']:
        return {'state': 'restore-unavailable'}
    current, registry = read_file(config), read_file(registry_path)
    if fingerprint(current, registry) != source.get('after'): return {'state': 'changed'}
    original = json.loads(old_registry)
    matching = [p for p in original['plugins'][plugin] if p.get('scope') == scope and (scope == 'user' or p.get('projectPath') == str(cwd))]
    if len(matching) != 1: return {'state': 'restore-unavailable'}
    operation = request['operation']
    write_file(store / (operation + '.before'), current or b'')
    write_file(store / (operation + '.registry-before'), registry or b'')
    result = dict(state='started', operation=operation, target=identity)
    write_file(record, json.dumps(result).encode())
    mutated = False
    try:
        restored = store / 'restored'; private_directory(restored)
        location = unpack_package(store / (source_id + '.plugin-before.tar'), restored / operation, source['packageHash'])
        if not location.is_dir(): raise ValueError('missing plugin root')
        matching[0]['installPath'] = str(location)
        if fingerprint(read_file(config), read_file(registry_path)) != source['after']:
            result['state'] = 'changed'
        else:
            mutated = True
            restored_registry = json.dumps(original).encode()
            write_file(registry_path, restored_registry)
            if source['existed']: write_file(config, before)
            else:
                try: config.unlink()
                except FileNotFoundError: pass
                if config.parent.is_dir():
                    fd = os.open(config.parent, os.O_RDONLY | os.O_DIRECTORY)
                    try: os.fsync(fd)
                    finally: os.close(fd)
            if read_file(config) != (before if source['existed'] else None): raise ValueError('restore not confirmed')
            if read_file(registry_path) != restored_registry: raise ValueError('registry restore not confirmed')
            result.update(state='package-restored', afterVersion=matching[0].get('version', ''), after=fingerprint(read_file(config), read_file(registry_path)))
    except Exception:
        result['state'] = 'unknown' if mutated else 'restore-unavailable'
    write_file(record, json.dumps(result).encode())
    return result

def install_operation(request, home, config, cwd, store, record, previous, run):
    plugin, scope, operation = request['plugin'], request['scope'], request['operation']
    identity = dict(plugin=plugin, scope=scope, directory=str(cwd), kind='install')
    if previous is not None:
        old = json.loads(previous)
        if old.get('target') != identity: return {'state': 'operation-conflict'}
        if old['state'] == 'started': old['state'] = 'unknown'
        return old
    def cli_json(args):
        with tempfile.TemporaryFile() as out, tempfile.TemporaryFile() as err:
            result = run(['claude', 'plugin'] + args, cwd=str(cwd), stdin=subprocess.DEVNULL, stdout=out, stderr=err, timeout=15)
            if result.returncode: raise ValueError('CLI query failed')
            out.seek(0); raw = out.read(8388609)
            if len(raw) > 8388608: raise ValueError('large output')
            return json.loads(raw)
    registry_path = home / '.claude/plugins/installed_plugins.json'
    before = read_file(config)
    registry = read_file(registry_path)
    settings = json.loads(before) if before is not None else {}
    if not isinstance(settings, dict): return {'state': 'invalid'}
    installed = json.loads(registry) if registry is not None else {}
    records = installed.get('plugins', {}).get(plugin, [])
    if any(p.get('scope') == scope and (scope == 'user' or p.get('projectPath') == str(cwd)) for p in records):
        return {'state': 'already-installed'}
    catalog = cli_json(['list', '--available', '--json'])
    candidates = [p for p in catalog['available'] if p.get('pluginId') == plugin]
    if len(candidates) != 1: return {'state': 'catalog-changed'}
    catalog_digest = hashlib.sha256(json.dumps(candidates[0], sort_keys=True).encode()).hexdigest()
    if catalog_digest != request.get('catalogFingerprint'): return {'state': 'catalog-changed'}
    digest = fingerprint(before, registry)
    if request['action'] == 'prepare-install':
        return dict(state='prepared', path=str(config), fingerprint=digest, catalogFingerprint=catalog_digest)
    if request.get('fingerprint') != digest: return {'state': 'changed'}
    write_file(store / (operation + '.before'), before if before is not None else b'')
    write_file(store / (operation + '.registry-before'), registry if registry is not None else b'')
    result = dict(state='started', operation=operation, target=identity, before=digest, existed=before is not None, registryExisted=registry is not None, catalogFingerprint=catalog_digest)
    write_file(record, json.dumps(result).encode())
    try:
        # Do not auto-accept marketplace-provided shell commands or headers helpers.
        with tempfile.TemporaryFile() as out, tempfile.TemporaryFile() as err:
            completed = run(['claude', 'plugin', 'install', plugin, '--scope', scope], cwd=str(cwd), stdin=subprocess.DEVNULL, stdout=out, stderr=err, timeout=45)
        actual = cli_json(['list', '--json'])
        matches = [p for p in actual if p.get('id') == plugin and p.get('scope') == scope and (scope == 'user' or p.get('projectPath') == str(cwd))]
        result['state'] = 'installed' if completed.returncode == 0 and len(matches) == 1 and not matches[0].get('errors') and os.path.isdir(matches[0].get('installPath', '')) else 'unknown'
        result['after'] = fingerprint(read_file(config), read_file(registry_path))
    except Exception: result['state'] = 'unknown'
    write_file(record, json.dumps(result).encode())
    return result

def execute(request, home=None, run=subprocess.run):
    home = Path(home or os.path.expanduser('~')).resolve()
    base = home / '.claude'
    if Path(os.environ.get('CLAUDE_CONFIG_DIR', str(base))).resolve() != base:
        return {'state': 'unsupported-config-home'}
    action = request.get('action')
    operation = request.get('operation', '')
    if action not in ('prepare', 'set', 'status', 'review', 'restore', 'prepare-install', 'install', 'uninstall', 'update', 'rollback') or not re.fullmatch(r'[a-f0-9]{32}', operation):
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
        if action == 'review':
            result = json.loads(previous) if previous else {'state': 'missing'}
            if result.get('state') in ('started', 'unknown', 'unconfirmed', 'missing'):
                result['previousState'] = result['state']
                result['state'] = 'reviewed'
                result['manuallyReviewed'] = True
                # Keep a tombstone even when no receipt exists, so a delayed original
                # request cannot execute after the user has cleared the uncertainty.
                write_file(record, json.dumps(result).encode())
            return result
        if action == 'status':
            if previous is None: return {'state': 'missing'}
            result = json.loads(previous)
            if result['state'] == 'started': result['state'] = 'unknown'
            return result
        if action == 'rollback': return rollback_package(request, home, store, record, previous)
        source = None
        if action == 'restore':
            source_id = request.get('restores', '')
            if not re.fullmatch(r'[a-f0-9]{32}', source_id) or source_id == operation:
                return {'state': 'invalid'}
            source_raw = read_file(store / (source_id + '.json'))
            source = json.loads(source_raw) if source_raw else None
            if source is None or source.get('state') != 'configured' or not source.get('after'):
                return {'state': 'restore-unavailable'}
            request = dict(source['target'], restores=source_id, fingerprint=source['after'])
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
        if action in ('prepare-install', 'install'):
            return install_operation(request, home, config, cwd, store, record, previous, run)
        def snapshot(require_installed=True):
            raw = read_file(config)
            registry = read_file(base / 'plugins' / 'installed_plugins.json')
            parsed = json.loads(raw) if raw is not None else {}
            installed = json.loads(registry) if registry is not None else {}
            if not isinstance(parsed, dict) or not isinstance(parsed.get('enabledPlugins', {}), dict):
                raise ValueError('invalid settings')
            entries = installed.get('plugins', {}).get(plugin, [])
            matches = [p for p in entries if p.get('scope') == scope and (scope == 'user' or p.get('projectPath') == str(cwd))]
            if len(matches) != (1 if require_installed else 0): raise ValueError('unexpected installation records')
            digest = fingerprint(raw, registry)
            return raw, parsed, digest
        desired = request.get('enabled')
        if action == 'set' and type(desired) is not bool:
            return {'state': 'invalid'}
        identity = dict(plugin=plugin, scope=scope, directory=str(cwd), enabled=desired)
        if action in ('uninstall', 'update'): identity['kind'] = action
        if source is not None: identity['restores'] = request['restores']
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
        restore_bytes = None
        if source is not None:
            restore_bytes = read_file(store / (request['restores'] + '.before'))
            if restore_bytes is None or fingerprint(restore_bytes if source['existed'] else None, read_file(base / 'plugins' / 'installed_plugins.json')) != source['before']:
                return {'state': 'restore-unavailable'}
        # No execution until the prior config and durable intent have both been saved.
        backup = store / (operation + '.before')
        write_file(backup, raw if raw is not None else b'')
        result = dict(state='started', operation=operation, target=identity, existed=raw is not None, before=digest)
        if action in ('uninstall', 'update'):
            registry = read_file(base / 'plugins/installed_plugins.json')
            write_file(store / (operation + '.registry-before'), registry)
            installed = json.loads(registry)['plugins'][plugin]
            target = next(p for p in installed if p.get('scope') == scope and (scope == 'user' or p.get('projectPath') == str(cwd)))
            result['beforeVersion'] = target.get('version') if isinstance(target.get('version'), str) else ''
            result['packageHash'] = package_backup(target['installPath'], store / (operation + '.plugin-before.tar'))
            if snapshot()[2] != digest: return {'state': 'changed'}
        write_file(record, json.dumps(result).encode())
        try:
            if action == 'uninstall':
                with tempfile.TemporaryFile() as out, tempfile.TemporaryFile() as err:
                    completed = run(['claude', 'plugin', 'uninstall', plugin, '--scope', scope, '--keep-data'], cwd=str(cwd), stdin=subprocess.DEVNULL, stdout=out, stderr=err, timeout=30)
                _, _, after_digest = snapshot(False)
                result['state'] = 'uninstalled' if completed.returncode == 0 else 'unknown'
            elif action == 'update':
                with tempfile.TemporaryFile() as out, tempfile.TemporaryFile() as err:
                    completed = run(['claude', 'plugin', 'update', plugin, '--scope', scope], cwd=str(cwd), stdin=subprocess.DEVNULL, stdout=out, stderr=err, timeout=45)
                _, _, after_digest = snapshot()
                after_registry = json.loads(read_file(base / 'plugins/installed_plugins.json'))
                updated = next(p for p in after_registry['plugins'][plugin] if p.get('scope') == scope and (scope == 'user' or p.get('projectPath') == str(cwd)))
                result['afterVersion'] = updated.get('version') if isinstance(updated.get('version'), str) else ''
                result['state'] = 'updated' if completed.returncode == 0 and os.path.isdir(updated.get('installPath', '')) else 'unknown'
            elif source is not None:
                if snapshot()[2] != digest:
                    result['state'] = 'changed'
                    write_file(record, json.dumps(result).encode())
                    return result
                if source['existed']:
                    write_file(config, restore_bytes)
                else:
                    config.unlink()
                    fd = os.open(config.parent, os.O_RDONLY | os.O_DIRECTORY)
                    try: os.fsync(fd)
                    finally: os.close(fd)
                restored, _, after_digest = snapshot()
                result['state'] = 'restored' if restored == (restore_bytes if source['existed'] else None) else 'unknown'
            else:
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
