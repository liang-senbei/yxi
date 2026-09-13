import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
from types import SimpleNamespace

source = Path(__file__).resolve().parents[1] / 'android/desktop/src/main/resources/app/yxi/desktop/plugin-operation.py'
spec = importlib.util.spec_from_file_location('operation', source)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
with tempfile.TemporaryDirectory(prefix='yxi-plugin-operation-') as root:
    home = Path(root)
    base = home / '.claude'
    (base / 'plugins').mkdir(parents=True)
    plugin = home / 'plugin'
    (plugin / '.claude-plugin').mkdir(parents=True)
    (plugin / '.claude-plugin/plugin.json').write_text('{"name":"sample","version":"1.0.0"}')
    config = base / 'settings.json'
    original = {'enabledPlugins': {'sample@fixture': True}, 'env': {'TOKEN': 'PRIVATE_VALUE'}}
    config.write_text(json.dumps(original))
    (base / 'plugins/installed_plugins.json').write_text(json.dumps({'version': 2, 'plugins': {'sample@fixture': [{'scope': 'user', 'installPath': str(plugin), 'version': '1.0.0'}]}}))
    os.environ.update(HOME=root, CLAUDE_CONFIG_DIR=str(base))
    target = dict(operation='a'*32, plugin='sample@fixture', scope='user', directory=root)
    prepared = module.execute(dict(target, action='prepare'), root)
    assert prepared['state'] == 'prepared'
    calls = []
    def fake(argv, **kwargs):
        calls.append(argv)
        value = json.loads(config.read_text())
        value['enabledPlugins']['sample@fixture'] = False
        config.write_text(json.dumps(value))
        return SimpleNamespace(returncode=0)
    request = dict(target, action='set', enabled=False, fingerprint=prepared['fingerprint'])
    result = module.execute(request, root, fake)
    assert result['state'] == 'configured'
    assert 'PRIVATE_VALUE' not in json.dumps(result)
    assert module.execute(request, root, fake)['state'] == 'configured' and len(calls) == 1
    assert module.execute(dict(request, enabled=True), root, fake)['state'] == 'operation-conflict'
    assert module.execute(dict(action='status', operation='a'*32), root)['state'] == 'configured'
    backup = home / '.yxi/plugin-operations' / ('a'*32 + '.before')
    assert json.loads(backup.read_text()) == original
    assert backup.stat().st_mode & 0o077 == 0
    assert module.execute(dict(request, operation='b'*32), root, fake)['state'] == 'changed'
    current = module.execute(dict(target, operation='c'*32, action='prepare'), root)
    def interrupted(*args, **kwargs): raise subprocess.TimeoutExpired('fixture', 20)
    lost = dict(request, operation='c'*32, fingerprint=current['fingerprint'])
    assert module.execute(lost, root, interrupted)['state'] == 'unknown'
    assert module.execute(lost, root, fake)['state'] == 'unknown' and len(calls) == 1
    # Actual CLI disable in the isolated config home; no production plugin is touched.
    config.write_text(json.dumps(original))
    fresh = module.execute(dict(target, operation='d'*32, action='prepare'), root)
    actual = module.execute(dict(request, operation='d'*32, fingerprint=fresh['fingerprint']), root)
    assert actual['state'] == 'configured', actual
    assert json.loads(config.read_text())['env'] == original['env']
    restore = dict(action='restore', operation='2'*32, restores='d'*32)
    assert module.execute(restore, root)['state'] == 'restored'
    assert json.loads(config.read_text()) == original
    assert module.execute(restore, root)['state'] == 'restored'
    assert module.execute(dict(action='status', operation='2'*32), root)['state'] == 'restored'
    next_target = dict(target, operation='3'*32)
    prep = module.execute(dict(next_target, action='prepare'), root)
    module.execute(dict(next_target, action='set', enabled=False, fingerprint=prep['fingerprint']), root)
    exact_after = config.read_bytes()
    changed = json.loads(config.read_text()); changed['laterEdit'] = True
    config.write_text(json.dumps(changed))
    assert module.execute(dict(action='restore', operation='4'*32, restores='3'*32), root)['state'] == 'changed'
    assert json.loads(config.read_text())['laterEdit'] is True
    config.write_bytes(exact_after)
    (home / '.yxi/plugin-operations' / ('3'*32 + '.before')).write_text('{}')
    assert module.execute(dict(action='restore', operation='5'*32, restores='3'*32), root)['state'] == 'restore-unavailable'
    for scope, letter in [('project', 'e'), ('local', 'f')]:
        project = home / ('project-' + scope)
        (project / '.claude').mkdir(parents=True)
        path = project / '.claude' / ('settings.local.json' if scope == 'local' else 'settings.json')
        path.write_text(json.dumps(original))
        registry = base / 'plugins/installed_plugins.json'
        data = json.loads(registry.read_text())
        data['plugins']['sample@fixture'].append({'scope': scope, 'projectPath': str(project), 'installPath': str(plugin), 'version': '1.0.0'})
        registry.write_text(json.dumps(data))
        scoped = dict(target, operation=letter*32, scope=scope, directory=str(project))
        prep = module.execute(dict(scoped, action='prepare'), root)
        change = module.execute(dict(scoped, action='set', enabled=False, fingerprint=prep['fingerprint']), root)
        assert change['state'] == 'configured', change
        assert json.loads(path.read_text())['enabledPlugins']['sample@fixture'] is False
        assert json.loads(path.read_text())['env'] == original['env']
    enable_target = dict(target, operation='1'*32)
    prep = module.execute(dict(enable_target, action='prepare'), root)
    enabled = module.execute(dict(enable_target, action='set', enabled=True, fingerprint=prep['fingerprint']), root)
    assert enabled['state'] == 'configured', enabled
    assert json.loads(config.read_text())['enabledPlugins']['sample@fixture'] is True
    config.unlink()
    absent_target = dict(target, operation='6'*32)
    prep = module.execute(dict(absent_target, action='prepare'), root)
    def create_config(*args, **kwargs):
        config.write_text('{"enabledPlugins":{"sample@fixture":false}}')
        return SimpleNamespace(returncode=0)
    assert module.execute(dict(absent_target, action='set', enabled=False, fingerprint=prep['fingerprint']), root, create_config)['state'] == 'configured'
    assert module.execute(dict(action='restore', operation='7'*32, restores='6'*32), root)['state'] == 'restored'
    assert not config.exists()
print('plugin operation: backup, idempotency, conflict, lost response, real CLI disable passed')
