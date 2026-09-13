import base64, fcntl, json, os, pathlib, re, shutil, socket, stat, subprocess, sys

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
    def snapshot():
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
        fields = run('display-message', '-p', '-t', target + ':', '#{pid}:#{session_id}:#{session_created}\t#{pane_id}\t#{pane_dead}\t#{pane_dead_status}').stdout.strip().split('\t')
        runtime, pane, dead = fields[:3]
        return dict(state='exited' if dead == '1' else ('running' if launched else 'starting'), runtime=runtime, pane=pane, config=signature,
                    matchesConfig=signature == cfg['config'] if 'config' in cfg else None, exitCode=int(fields[3]) if len(fields)>3 and fields[3].isdigit() else None,
                    log=run('capture-pane', '-p', '-S', '-200', '-t', pane, check=False).stdout[-32000:])
    with (root / 'lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        current = snapshot()
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
