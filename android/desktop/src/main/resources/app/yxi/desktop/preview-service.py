import base64, fcntl, json, os, pathlib, re, shutil, socket, stat, subprocess, sys, http.client, time, ipaddress

def process_info(pid):
    try:
        fields = pathlib.Path('/proc', str(pid), 'stat').read_text(errors='replace').rpartition(')')[2].split()
        return int(fields[1]), fields[19]
    except (OSError, ValueError, IndexError):
        return None

def listeners(port, host):
    found = set()
    for name in ('tcp', 'tcp6'):
        path = pathlib.Path('/proc/net', name)
        if not path.exists():
            if name == 'tcp': raise OSError('Linux socket table unavailable')
            continue
        for row in path.read_text().splitlines()[1:]:
            fields = row.split()
            address, number = fields[1].split(':')
            if fields[3] != '0A' or int(number, 16) != port: continue
            # Include IPv6 wildcard because it can also accept IPv4. Never
            # certify a port that has another matching listener outside our tree.
            data = bytes.fromhex(address)
            if sys.byteorder == 'little': data = b''.join(data[i:i+4][::-1] for i in range(0, len(data), 4))
            ip = ipaddress.ip_address(data)
            matches = ((ip.version == 4 and (ip.is_unspecified or str(ip) == host)) or
                       (ip.version == 6 and (ip.is_unspecified or str(getattr(ip, 'ipv4_mapped', None)) == host))) if host == '127.0.0.1' else (ip.version == 6 and (ip.is_unspecified or str(ip) == '::1'))
            if matches:
                found.add(fields[9])
    return found

def owned_listener(pid, port, host):
    initial = process_info(pid)
    if initial is None: return 'unverified', None
    table = {}
    for entry in pathlib.Path('/proc').iterdir():
        if entry.name.isdigit():
            info = process_info(int(entry.name))
            if info is not None: table[int(entry.name)] = info
    children = {pid}
    while True:
        expanded = children | {child for child, info in table.items() if info[0] in children}
        if expanded == children: break
        children = expanded
    sockets = set()
    for child in children:
        expected = table.get(child)
        if expected is None or process_info(child) != expected: continue
        local = set()
        try:
            for descriptor in pathlib.Path('/proc', str(child), 'fd').iterdir():
                try:
                    match = re.fullmatch(r'socket:\[([0-9]+)\]', os.readlink(descriptor))
                    if match: local.add(match.group(1))
                except OSError: pass
        except OSError: continue
        if process_info(child) == expected: sockets |= local
    candidates = listeners(port, host)
    if process_info(pid) != initial: return 'changed', None
    if not candidates: return 'not-listening', None
    if not candidates.issubset(sockets): return 'unmatched-listener', None
    return 'owned', (initial, frozenset(candidates))

def probe_http(pid, port, host, path):
    checked = int(time.time() * 1000)
    try:
        ownership, identity = owned_listener(pid, port, host)
        if ownership != 'owned': return dict(readiness=ownership, checkedAt=checked)
        connection = http.client.HTTPConnection(host, port, timeout=1)
        try:
            connection.request('GET', path, headers={'Connection': 'close'})
            response = connection.getresponse()
            status = response.status
            if 200 <= status < 300: response.read(1024)
        finally: connection.close()
        after, current = owned_listener(pid, port, host)
        if after != 'owned' or current != identity: return dict(readiness='changed', checkedAt=checked)
        return dict(readiness='ready' if 200 <= status < 300 else 'http-response', httpStatus=status, checkedAt=checked, probeHost=host)
    except (OSError, http.client.HTTPException):
        return dict(readiness='unverified', checkedAt=checked)

def readiness(pid, port, path):
    results = []
    for host in ('127.0.0.1', '::1'):
        result = probe_http(pid, port, host, path)
        result['probePath'] = path
        if result['readiness'] in ('ready', 'changed'): return result
        results.append(result)
    for state in ('http-response', 'unmatched-listener', 'unverified', 'not-listening'):
        for result in results:
            if result['readiness'] == state: return result

def emit(state, **fields):
    print('__YXI_PREVIEW__:' + json.dumps(dict(state=state, **fields), ensure_ascii=False))

def main():
    cfg = json.loads(base64.b64decode(sys.argv[1]))
    for key in ('project',) + tuple(key for key in ('config', 'expectedConfig') if key in cfg):
        if not re.fullmatch('[a-f0-9]{64}', cfg[key]):
            raise ValueError('Invalid preview identity')
    action = cfg['action']
    if action not in ('start', 'status', 'stop'):
        raise ValueError('Invalid preview action')
    probe_path = cfg.get('probePath', '/')
    if not isinstance(probe_path, str) or not probe_path.startswith('/') or probe_path.startswith('//') or len(probe_path)>2048 or not probe_path.isascii() or '#' in probe_path or any(ord(c)<32 or ord(c)==127 for c in probe_path):
        raise ValueError('Invalid readiness path')
    if action == 'start':
        if not isinstance(cfg['port'], int) or not 1 <= cfg['port'] <= 65535:
            raise ValueError('Invalid preview port')
        if not os.path.isabs(cfg['directory']) or not cfg['command'] or '\0' in cfg['command']:
            raise ValueError('Invalid preview command or directory')
    if action == 'stop' and not re.fullmatch(r'[0-9]+:\$[0-9]+:[0-9]+', cfg['runtime']):
        raise ValueError('Invalid runtime identity')
    tmux = shutil.which('tmux')
    if not tmux:
        emit('missing-tool', message='服务端未安装tmux'); return
    root = pathlib.Path.home() / '.yxi' / 'preview-runtime'
    if not root.exists():
        if action != 'start':
            emit('missing'); return
        root.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        root.mkdir(mode=0o700)
        (root / 'owner').write_text('yxi-preview-v1')
    info = root.lstat()
    if root.is_symlink() or not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) & 0o077:
        raise ValueError('Preview runtime directory is not private')
    if (root / 'owner').read_text() != 'yxi-preview-v1':
        raise ValueError('Unrecognized preview runtime directory')
    socket_path = root / 'tmux.sock'
    if socket_path.is_symlink():
        raise ValueError('Preview socket must not be a symlink')
    base = [tmux, '-f', '/dev/null', '-S', str(socket_path)]
    name = 'pv-' + cfg['project']
    target = '=' + name
    def run(*args, check=True):
        result = subprocess.run(base + list(args), stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, errors='replace', timeout=8)
        if check and result.returncode:
            raise RuntimeError(result.stderr.strip() or 'tmux operation failed')
        return result
    def snapshot(probe=False):
        if run('has-session', '-t', target, check=False).returncode:
            return None
        sid = run('display-message', '-p', '-t', target + ':', '#{session_id}').stdout.strip()
        def environment(key):
            return run('show-environment', '-t', sid, key, check=False).stdout.strip().partition('=')[2]
        owner = environment('YXI_PREVIEW_PROJECT')
        if owner != cfg['project']:
            return dict(state='unowned')
        signature = environment('YXI_PREVIEW_CONFIG')
        launched = environment('YXI_PREVIEW_PHASE') == 'launched'
        fields = run('display-message', '-p', '-t', target + ':', '#{pid}:#{session_id}:#{session_created}\t#{pane_id}\t#{pane_dead}\t#{pane_dead_status}\t#{pane_pid}').stdout.strip().split('\t')
        runtime, pane, dead = fields[:3]
        result = dict(state='exited' if dead == '1' else ('running' if launched else 'starting'), runtime=runtime, pane=pane, config=signature,
                    matchesConfig=signature == cfg['config'] if 'config' in cfg else None, exitCode=int(fields[3]) if len(fields)>3 and fields[3].isdigit() else None,
                    log=run('capture-pane', '-p', '-S', '-200', '-t', pane, check=False).stdout[-32000:])
        port = environment('YXI_PREVIEW_PORT')
        if probe and result['state'] == 'running':
            result['probePath'] = probe_path
            if port.isdigit() and len(fields) > 4 and fields[4].isdigit() and 1 <= int(port) <= 65535:
                result.update(readiness(int(fields[4]), int(port), probe_path))
            else: result['readiness'] = 'unverified'
        return result
    with (root / 'lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        current = snapshot(probe=action == 'status')
        if action == 'status':
            emit(**current) if current else emit('missing')
            return
        if action == 'stop':
            if not current:
                emit('missing'); return
            if current.get('runtime') != cfg['runtime'] or current.get('config') != cfg['expectedConfig']:
                emit('changed', message='预览进程已变化，未停止'); return
            # Validate and stop in one server connection, so a restarted server
            # with reused session IDs cannot receive the old stop operation.
            condition = ('#{&&:#{==:#{pid}:#{session_id}:#{session_created},' + cfg['runtime'] +
                         '},#{&&:#{==:#{YXI_PREVIEW_CONFIG},' + cfg['expectedConfig'] +
                         '},#{==:#{YXI_PREVIEW_PROJECT},' + cfg['project'] + '}}}')
            sid = cfg['runtime'].split(':')[1]
            run('if-shell', '-F', '-t', current['pane'], condition, "kill-session -t '" + sid + "'")
            emit('stopped' if snapshot() is None else 'changed')
            return
        if current:
            if current['state'] == 'unowned':
                emit('unowned')
            elif current.get('matchesConfig') is False:
                emit('configuration-conflict', runtime=current.get('runtime'), config=current.get('config'))
            else:
                emit(**current, reused=True)
            return
        cwd = os.path.realpath(cfg['directory'])
        if not os.path.isdir(cwd):
            emit('missing-directory'); return
        for address in ('127.0.0.1', '::1'):
            try:
                connection = socket.create_connection((address, cfg['port']), timeout=0.2)
            except OSError:
                continue
            connection.close()
            emit('port-busy', message='目标端口已有服务，未启动或停止任何进程'); return
        pane = run('new-session', '-d', '-P', '-F', '#{pane_id}', '-s', name, '-c', cwd,
                   '-e', 'YXI_PREVIEW_PROJECT=' + cfg['project'], '-e', 'YXI_PREVIEW_CONFIG=' + cfg['config'],
                   '-e', 'YXI_PREVIEW_PORT=' + str(cfg['port']),
                   '-e', 'YXI_PREVIEW_PHASE=starting', '/bin/sleep', '86400').stdout.strip()
        if not re.fullmatch(r'%[0-9]+', pane):
            raise RuntimeError('Preview pane was not confirmed')
        sid = run('display-message', '-p', '-t', pane, '#{session_id}').stdout.strip()
        window = run('display-message', '-p', '-t', pane, '#{window_id}').stdout.strip()
        run('set-window-option', '-t', window, 'remain-on-exit', 'on')
        run('respawn-pane', '-k', '-t', pane, '-c', cwd, '/bin/sh', '-c', cfg['command'])
        run('set-environment', '-t', sid, 'YXI_PREVIEW_PHASE', 'launched')
        result = snapshot()
        emit(**result, reused=False) if result else emit('unknown')

try:
    main()
except subprocess.TimeoutExpired:
    emit('unknown', message='开发服务操作超时，请查询状态后再决定下一步')
except Exception as error:
    emit('error', message=str(error))
