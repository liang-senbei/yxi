#!/usr/bin/env python3
"""Install/remove the user-local Claude SessionStart collaboration hook."""
import argparse
import json
import os
from pathlib import Path
import shlex
import stat
import tempfile
import uuid

parser = argparse.ArgumentParser()
parser.add_argument('action', choices=['install', 'remove'])
args = parser.parse_args()
hub = Path.home() / '.local' / 'bin' / 'yxi-hub'
if args.action == 'install' and not os.access(hub, os.X_OK):
    raise SystemExit('请先安装可执行的 ~/.local/bin/yxi-hub')
settings = (Path.home() / '.claude' / 'settings.json').resolve()
original = settings.read_bytes() if settings.exists() else None
data = json.loads(original) if original is not None else {}
if not isinstance(data, dict): raise SystemExit('settings.json 不是对象，未修改')
hooks = data.setdefault('hooks', {})
entries = hooks.setdefault('SessionStart', [])
if not isinstance(entries, list): raise SystemExit('SessionStart 格式不支持，未修改')
command = shlex.quote(str(hub)) + ' context'
found = False
for entry in entries:
    if not isinstance(entry, dict) or not isinstance(entry.get('hooks', []), list):
        raise SystemExit('已有钩子格式不支持，未修改')
    kept = []
    for hook in entry.get('hooks', []):
        if not isinstance(hook, dict): raise SystemExit('已有钩子格式不支持，未修改')
        try: words = shlex.split(hook.get('command', ''))
        except ValueError: words = []
        simple_hub = len(words) == 2 and Path(words[0]).name == 'yxi-hub' and words[1] == 'context'
        if args.action == 'install' and simple_hub and hook.get('type') == 'command':
            hook = dict(hook, command=command)
            hook.pop('async', None)
            found = True
        if args.action == 'remove' and hook.get('command') == command and hook.get('type') == 'command':
            continue
        kept.append(hook)
    entry['hooks'] = kept
entries[:] = [entry for entry in entries if entry.get('hooks')]
if args.action == 'install' and not found:
    entries.append({'hooks': [{'type': 'command', 'command': command}]})
updated = (json.dumps(data, ensure_ascii=False, indent=2) + '\n').encode()
if original == updated:
    print('钩子配置已是目标状态')
    raise SystemExit(0)
settings.parent.mkdir(parents=True, exist_ok=True)
if original is not None:
    backup = settings.with_name('settings.before-hub-' + uuid.uuid4().hex + '.json')
    with backup.open('xb') as f: f.write(original)
    backup.chmod(0o600)
    print('原配置备份：' + str(backup))
fd, temporary = tempfile.mkstemp(prefix='settings-hub-', dir=settings.parent)
try:
    with os.fdopen(fd, 'wb') as f:
        f.write(updated); f.flush(); os.fsync(f.fileno())
    if original is not None:
        os.chmod(temporary, stat.S_IMODE(settings.stat().st_mode))
    if (settings.read_bytes() if settings.exists() else None) != original:
        raise SystemExit('配置已被其他进程修改，请重新执行')
    os.replace(temporary, settings)
finally:
    if os.path.exists(temporary): os.unlink(temporary)
print('已' + ('安装' if args.action == 'install' else '移除') + '协作上下文钩子；未发送消息，未启动 Agent')
