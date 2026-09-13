import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import tarfile

source = Path(__file__).resolve().parents[1] / 'android/desktop/src/main/resources/app/yxi/desktop/plugin-operation.py'
spec = importlib.util.spec_from_file_location('operation', source)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
with tempfile.TemporaryDirectory(prefix='yxi-plugin-install-') as root:
    home = Path(root)
    os.environ.update(HOME=root, CLAUDE_CONFIG_DIR=str(home / '.claude'))
    market = home / 'market'
    (market / '.claude-plugin').mkdir(parents=True)
    plugin = market / 'plugins/sample'
    (plugin / '.claude-plugin').mkdir(parents=True)
    (plugin / '.claude-plugin/plugin.json').write_text('{"name":"sample","version":"1.0.0","description":"Isolated test plugin"}')
    (market / '.claude-plugin/marketplace.json').write_text(json.dumps({'name': 'yxi-fixture', 'owner': {'name': 'Yxi test'}, 'plugins': [{'name': 'sample', 'source': './plugins/sample', 'description': 'Isolated test plugin', 'version': '1.0.0'}]}))
    added = subprocess.run(['claude', 'plugin', 'marketplace', 'add', str(market)], cwd=root, capture_output=True, timeout=30)
    assert added.returncode == 0, added.stderr.decode()
    catalog = json.loads(subprocess.check_output(['claude', 'plugin', 'list', '--available', '--json'], cwd=root))
    entry = next(p for p in catalog['available'] if p['pluginId'] == 'sample@yxi-fixture')
    digest = hashlib.sha256(json.dumps(entry, sort_keys=True).encode()).hexdigest()
    request = dict(action='prepare-install', operation='8'*32, plugin=entry['pluginId'], scope='user', directory=root, catalogFingerprint=digest)
    prepared = module.execute(request, root)
    assert prepared['state'] == 'prepared', prepared
    install = dict(request, action='install', fingerprint=prepared['fingerprint'])
    assert module.execute(dict(install, catalogFingerprint='0'*64), root)['state'] == 'catalog-changed'
    result = module.execute(install, root)
    assert result['state'] == 'installed', result
    assert module.execute(install, root)['state'] == 'installed'
    assert module.execute(dict(action='status', operation='8'*32), root)['state'] == 'installed'
    assert (home / '.yxi/plugin-operations' / ('8'*32 + '.registry-before')).exists()
    fresh = json.loads(subprocess.check_output(['claude', 'plugin', 'list', '--available', '--json'], cwd=root))
    assert all(p['pluginId'] != entry['pluginId'] for p in fresh['available'])
    assert module.execute(dict(request, operation='9'*32), root)['state'] == 'already-installed'
    settings_path = home / '.claude/settings.json'
    settings = json.loads(settings_path.read_text())
    settings['enabledPlugins']['other@yxi-fixture'] = True
    settings_path.write_text(json.dumps(settings))
    data = home / '.claude/plugins/data/sample@yxi-fixture'
    data.mkdir(parents=True); (data / 'keep.txt').write_text('retained data')
    remove_target = dict(action='prepare', operation='b'*32, plugin=entry['pluginId'], scope='user', directory=root)
    prep = module.execute(remove_target, root)
    remove = dict(remove_target, action='uninstall', fingerprint=prep['fingerprint'])
    removed = module.execute(remove, root)
    assert removed['state'] == 'uninstalled', removed
    assert module.execute(remove, root)['state'] == 'uninstalled'
    assert (data / 'keep.txt').read_text() == 'retained data'
    assert json.loads(settings_path.read_text())['enabledPlugins']['other@yxi-fixture'] is True
    archive = home / '.yxi/plugin-operations' / ('b'*32 + '.plugin-before.tar')
    assert archive.stat().st_mode & 0o077 == 0
    assert hashlib.sha256(archive.read_bytes()).hexdigest() == removed['packageHash']
    with tarfile.open(archive) as backup:
        assert json.load(backup.extractfile('plugin/.claude-plugin/plugin.json'))['name'] == 'sample'
    actual = json.loads(subprocess.check_output(['claude', 'plugin', 'list', '--json'], cwd=root))
    assert all(p['id'] != entry['pluginId'] for p in actual)
    project = home / 'project'; project.mkdir()
    # Restore only the fixture registry/settings to a pre-install state to exercise
    # a fresh project-scope attempt without making another marketplace.
    (home / '.claude/plugins/installed_plugins.json').write_text('{"version":2,"plugins":{}}')
    (home / '.claude/settings.json').write_text('{}')
    fresh = json.loads(subprocess.check_output(['claude', 'plugin', 'list', '--available', '--json'], cwd=root))
    current = next(p for p in fresh['available'] if p['pluginId'] == entry['pluginId'])
    refreshed = hashlib.sha256(json.dumps(current, sort_keys=True).encode()).hexdigest()
    pending = dict(request, operation='a'*32, scope='project', directory=str(project), catalogFingerprint=refreshed)
    prep = module.execute(pending, root)
    assert prep['state'] == 'prepared', prep
    calls = []
    def interrupted(argv, **kwargs):
        if argv[2] == 'install':
            calls.append(argv)
            raise subprocess.TimeoutExpired('fixture install', 45)
        return subprocess.run(argv, **kwargs)
    pending = dict(pending, action='install', fingerprint=prep['fingerprint'])
    assert module.execute(pending, root, interrupted)['state'] == 'unknown'
    assert module.execute(pending, root, interrupted)['state'] == 'unknown'
    assert len(calls) == 1
print('real local marketplace: prepare, changed entry refusal, install, runner recognition, repeat/status passed')
