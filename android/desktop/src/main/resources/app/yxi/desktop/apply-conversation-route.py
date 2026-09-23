"""Process-scoped Claude route switch; credentials never appear in argv or receipts."""
import hashlib
import json
import os
import pathlib
import re
import shlex
import stat
import subprocess
import sys
import tempfile
import time


def tm(*args):
    return subprocess.check_output(['tmux', *args], text=True).rstrip('\n')


def secure_read(path):
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(fd, 'rb') as source:
        info = os.fstat(source.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077:
            raise ValueError('private configuration permissions invalid')
        data = source.read(2097153)
    if len(data) > 2097152:
        raise ValueError('configuration too large')
    return data


def env_of(pid):
    return dict(row.split('=', 1) for row in (pathlib.Path('/proc') / pid / 'environ').read_bytes().decode().split('\0') if '=' in row)


def records(environment, name):
    registry = pathlib.Path(environment.get('CLAUDE_CONFIG_DIR', str(pathlib.Path(environment['HOME']) / '.claude'))) / 'sessions'
    result = []
    for path in registry.glob('*.json'):
        try:
            item = json.loads(path.read_text())
            if str(item.get('tmux', '')).startswith(name + ':'):
                result.append(item)
        except (ValueError, OSError):
            pass
    return result


def same_pane(name, runtime, pane):
    return (tm('display-message', '-p', '-t', pane, '#{pid}:#{session_id}:#{session_created}') == runtime
            and tm('display-message', '-p', '-t', '=' + name + ':', '#{pane_id}') == pane)


def check_path(path, home):
    root = pathlib.Path(home) / '.yxi' / 'agent-settings'
    p = pathlib.Path(path)
    if p.parent != root or p.resolve() != p or not re.fullmatch(r'[0-9a-f-]{36}\.json', p.name):
        raise ValueError('invalid private configuration path')


def replace_private(path, data):
    fd, staged = tempfile.mkstemp(prefix='.route-', dir=pathlib.Path(path).parent)
    try:
        with os.fdopen(fd, 'wb') as output:
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
        os.replace(staged, path)
    finally:
        if os.path.exists(staged):
            os.unlink(staged)


def apply(args):
    name, runtime, pane, pid, exe, expected, path, wanted, mode = args
    if mode not in ('manual', 'auto', 'bypassPermissions', 'plan', 'acceptEdits', 'dontAsk'):
        raise ValueError('unknown permission mode')

    def identity():
        if not same_pane(name, runtime, pane) or os.path.realpath('/proc/' + pid + '/exe') != exe:
            raise ValueError('process identity changed')
        if hashlib.sha256(tm('capture-pane', '-p', '-t', pane).encode()).hexdigest() != expected:
            raise ValueError('screen changed')

    identity()
    environment = env_of(pid)
    check_path(path, environment['HOME'])
    raw = secure_read(path)
    if hashlib.sha256(raw).hexdigest() != wanted:
        raise ValueError('configuration changed')
    request = json.loads(raw)
    token = request['token']
    if pathlib.Path(path).stem != token:
        raise ValueError('configuration token changed')
    found = [x for x in records(environment, name) if str(x.get('pid')) == pid]
    if len(found) != 1 or found[0].get('status') != 'idle':
        raise ValueError('runner is not idle')
    sid = found[0]['sessionId']
    if not re.fullmatch(r'[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}', sid):
        raise ValueError('invalid session identity')
    cwd = os.readlink('/proc/' + pid + '/cwd')
    argv = [x for x in pathlib.Path('/proc', pid, 'cmdline').read_bytes().decode().split('\0') if x]
    retained, previous_settings = [], []
    i = 1
    while i < len(argv):
        arg = argv[i]
        if arg in ('--resume', '-r', '--permission-mode', '--model', '--effort', '--name', '-n', '--settings', '--mcp-config'):
            if i + 1 >= len(argv):
                raise ValueError('incomplete launch option')
            value = argv[i + 1]
            if arg in ('--resume', '-r') and value != sid:
                raise ValueError('session identity mismatch')
            if arg in ('--name', '-n'):
                retained.extend([arg, value])
            if arg == '--settings':
                check_path(value, environment['HOME'])
                previous_settings.append(json.loads(secure_read(value)))
            if arg == '--mcp-config':
                mcp_path = pathlib.Path(value)
                mcp_root = pathlib.Path(environment['HOME']) / '.yxi' / 'shared-mcp'
                if mcp_path.parent != mcp_root or mcp_path.resolve() != mcp_path or not re.fullmatch(r'[a-f0-9]{32}\.json', mcp_path.name):
                    raise ValueError('unmanaged MCP configuration path')
                mcp_config = json.loads(secure_read(value))
                if not isinstance(mcp_config.get('mcpServers'), dict):
                    raise ValueError('invalid MCP configuration')
                retained.extend([arg, value])
            i += 2
        elif arg in ('--dangerously-skip-permissions', '--allow-dangerously-skip-permissions'):
            # Keep the capability flag, but the actual mode is the captured current mode.
            retained.append('--allow-dangerously-skip-permissions')
            i += 1
        elif arg == '--' and i + 2 == len(argv):
            break  # Startup prompt is already present in resumed history.
        else:
            raise ValueError('unsupported launch option')
    settings = request['settings']
    selected_env = settings.get('env', {})
    if not isinstance(selected_env, dict) or any(not isinstance(v, str) or '\0' in v for v in selected_env.values()):
        raise ValueError('invalid route environment')
    # Clear provider-specific values inherited from an earlier route, including user/project settings.
    known = {k for k in environment if k.startswith('ANTHROPIC_') or k == 'CLAUDE_CODE_SUBAGENT_MODEL'}
    known.update(('ANTHROPIC_BASE_URL', 'ANTHROPIC_API_KEY', 'ANTHROPIC_AUTH_TOKEN', 'ANTHROPIC_MODEL', 'CLAUDE_CODE_SUBAGENT_MODEL'))
    for role in ('OPUS', 'SONNET', 'HAIKU', 'FABLE'):
        known.update(('ANTHROPIC_DEFAULT_' + role + '_MODEL', 'ANTHROPIC_DEFAULT_' + role + '_MODEL_NAME'))
    config_root = pathlib.Path(environment.get('CLAUDE_CONFIG_DIR', str(pathlib.Path(environment['HOME']) / '.claude')))
    sources = [config_root / 'settings.json']
    for directory in [pathlib.Path(cwd), *pathlib.Path(cwd).parents]:
        sources.extend([directory / '.claude/settings.json', directory / '.claude/settings.local.json'])
    documents = previous_settings[:]
    for source in sources:
        if source.is_file():
            documents.append(json.loads(source.read_text()))
    for document in documents:
        known.update(k for k in document.get('env', {}) if k.startswith('ANTHROPIC_') or k == 'CLAUDE_CODE_SUBAGENT_MODEL')
    merged_env = {key: '' for key in known}
    merged_env.update(selected_env)
    settings['env'] = merged_env
    final = json.dumps(settings, ensure_ascii=False).encode()
    digest = hashlib.sha256(final).hexdigest()
    replace_private(path, final)
    environment.update(merged_env)
    environment['YXI_AGENT_SETTINGS_ID'] = token
    environment['YXI_AGENT_SETTINGS_DIGEST'] = digest
    launch = [exe, *retained, '--resume', sid, '--permission-mode', mode, '--settings', path]
    payload = {'exe': exe, 'argv': launch, 'env': environment, 'cwd': cwd}
    fd, capsule = tempfile.mkstemp(prefix='yxi-route-', suffix='.json')
    try:
        with os.fdopen(fd, 'w') as output:
            json.dump(payload, output)
            output.flush()
            os.fsync(output.fileno())
        identity()
        worker = "import json,os,sys;p=sys.argv[1];d=json.load(open(p));os.unlink(p);os.chdir(d['cwd']);os.execve(d['exe'],d['argv'],d['env'])"
        subprocess.run(['tmux', 'respawn-pane', '-k', '-t', pane, '-c', cwd,
                        shlex.join([sys.executable, '-c', worker, capsule])], check=True)
        for _ in range(100):
            if not os.path.exists(capsule):
                return {'status': 'restarted', 'sessionId': sid, 'settingsDigest': digest}
            time.sleep(.1)
        raise ValueError('restart worker did not consume configuration')
    finally:
        if os.path.exists(capsule):
            os.unlink(capsule)


def verify(args):
    name, runtime, pane, exe, sid, path, digest, token = args
    if not same_pane(name, runtime, pane) or hashlib.sha256(secure_read(path)).hexdigest() != digest:
        return {'status': 'changed'}
    # Registry alone can be stale; require a live process with the exact launch token and settings.
    parent = tm('display-message', '-p', '-t', pane, '#{pane_pid}')
    candidates = [parent]
    try:
        candidates += pathlib.Path('/proc', parent, 'task', parent, 'children').read_text().split()
    except OSError:
        pass
    for pid in candidates:
        try:
            if os.path.realpath('/proc/' + pid + '/exe') != exe:
                continue
            environment = env_of(pid)
            if environment.get('YXI_AGENT_SETTINGS_ID') != token or environment.get('YXI_AGENT_SETTINGS_DIGEST') != digest:
                continue
            argv = pathlib.Path('/proc', pid, 'cmdline').read_bytes().decode().split('\0')
            if '--settings' not in argv or argv[argv.index('--settings') + 1] != path:
                continue
            found = [x for x in records(environment, name) if str(x.get('pid')) == pid and x.get('sessionId') == sid]
            if len(found) != 1 or found[0].get('status') != 'idle':
                continue
            start = pathlib.Path('/proc', pid, 'stat').read_text().rsplit(')', 1)[1].split()[19]
            boot = pathlib.Path('/proc/sys/kernel/random/boot_id').read_text().strip()
            return {'status': 'verified', 'processIdentity': boot + ':' + pid + ':' + start}
        except (OSError, ValueError, IndexError):
            pass
    return {'status': 'waiting'}


def inspect_settings(args):
    name, runtime = args
    pane = tm('display-message', '-p', '-t', '=' + name + ':', '#{pane_id}')
    if not same_pane(name, runtime, pane):
        return {'status': 'changed'}
    parent = tm('display-message', '-p', '-t', pane, '#{pane_pid}')
    candidates = [parent]
    try:
        candidates += pathlib.Path('/proc', parent, 'task', parent, 'children').read_text().split()
    except OSError:
        pass
    for pid in candidates:
        try:
            executable = os.path.realpath('/proc/' + pid + '/exe')
            if pathlib.Path(executable).name != 'claude' and '/claude/' not in executable:
                continue
            environment = env_of(pid)
            token = environment.get('YXI_AGENT_SETTINGS_ID')
            if not token:
                continue
            argv = pathlib.Path('/proc', pid, 'cmdline').read_bytes().decode().split('\0')
            path = argv[argv.index('--settings') + 1]
            check_path(path, environment['HOME'])
            raw = secure_read(path)
            if pathlib.Path(path).stem != token or hashlib.sha256(raw).hexdigest() != environment.get('YXI_AGENT_SETTINGS_DIGEST'):
                return {'status': 'changed'}
            settings = json.loads(raw)
            allowed = {'ANTHROPIC_MODEL'} | {'ANTHROPIC_DEFAULT_' + role + '_MODEL' for role in ('OPUS', 'SONNET', 'HAIKU', 'FABLE')}
            values = settings.get('env', {})
            from urllib.parse import urlparse
            public = {k: settings[k] for k in ('model', 'availableModels') if k in settings}
            public['env'] = {k: v for k, v in values.items() if k in allowed}
            public['thirdParty'] = bool(values.get('ANTHROPIC_BASE_URL')) and urlparse(values['ANTHROPIC_BASE_URL']).hostname != 'api.anthropic.com'
            if not same_pane(name, runtime, pane):
                return {'status': 'changed'}
            return {'status': 'configured', 'settings': public}
        except (OSError, ValueError, IndexError):
            return {'status': 'changed'}
    return {'status': 'unmanaged'}


if __name__ == '__main__':
    try:
        result = {'apply': apply, 'verify': verify, 'inspect': inspect_settings}[sys.argv[1]](sys.argv[2:])
    except Exception as error:
        reasons = {
            'unsupported launch option': '当前启动参数暂不能完整保留，未重启会话',
            'process identity changed': '原运行器进程已变化，未重启会话',
            'screen changed': '终端内容已变化，请等待空闲后重试',
            'runner is not idle': '运行器尚未空闲，请等待任务结束',
            'configuration changed': '配置已被修改，请重新读取',
            'invalid private configuration path': '独立配置路径无法确认，未重启会话',
            'private configuration permissions invalid': '独立配置文件的访问权限不正确',
            'session identity mismatch': '原对话身份不一致，未重启会话',
            'invalid session identity': '无法确认原对话 ID，未重启会话',
            'unknown permission mode': '无法保留当前权限模式，未重启会话',
            'restart worker did not consume configuration': '重启结果尚未确认，请查看终端，不要重复提交',
        }
        result = {'status': 'failed', 'error': reasons.get(str(error), '会话状态、启动选项或配置无法确认；请查看终端')}
    print(json.dumps(result, ensure_ascii=False))
