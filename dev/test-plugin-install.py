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
    (plugin / '.claude-plugin/plugin.json').write_text('{"name":"sample","version":"1.1.0","description":"Updated local fixture"}')
    definition = market / '.claude-plugin/marketplace.json'
    value = json.loads(definition.read_text()); value['plugins'][0]['version'] = '1.1.0'
    definition.write_text(json.dumps(value))
    refresh = subprocess.run(['claude', 'plugin', 'marketplace', 'update', 'yxi-fixture'], cwd=root, capture_output=True, timeout=30)
    assert refresh.returncode == 0, refresh.stderr.decode()
    update_target = dict(action='prepare', operation='c'*32, plugin=entry['pluginId'], scope='user', directory=root)
    prep = module.execute(update_target, root)
    update = dict(update_target, action='update', fingerprint=prep['fingerprint'])
    upgraded = module.execute(update, root)
    assert upgraded['state'] == 'updated', upgraded
    assert upgraded['beforeVersion'] == '1.0.0' and upgraded['afterVersion'] == '1.1.0', upgraded
    assert module.execute(update, root)['state'] == 'updated'
    with tarfile.open(home / '.yxi/plugin-operations' / ('c'*32 + '.plugin-before.tar')) as backup:
        assert json.load(backup.extractfile('plugin/.claude-plugin/plugin.json'))['version'] == '1.0.0'
    assert json.loads(settings_path.read_text())['enabledPlugins']['other@yxi-fixture'] is True
    rolled = module.execute(dict(action='rollback', operation='d'*32, restores='c'*32), root)
    assert rolled['state'] == 'package-restored', rolled
    assert module.execute(dict(action='rollback', operation='d'*32, restores='c'*32), root)['state'] == 'package-restored'
    restored = json.loads(subprocess.check_output(['claude', 'plugin', 'list', '--json'], cwd=root))
    restored_entry = next(p for p in restored if p['id'] == entry['pluginId'])
    assert restored_entry['version'] == '1.0.0'
    assert json.loads((Path(restored_entry['installPath']) / '.claude-plugin/plugin.json').read_text())['version'] == '1.0.0'
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
    exact_config = settings_path.read_bytes()
    later = json.loads(exact_config); later['laterEdit'] = True
    settings_path.write_text(json.dumps(later))
    assert module.execute(dict(action='rollback', operation='2'*32, restores='b'*32), root)['state'] == 'changed'
    assert json.loads(settings_path.read_text())['laterEdit'] is True
    settings_path.write_bytes(exact_config)
    intact = archive.read_bytes()
    archive.write_bytes(intact + b'changed')
    assert module.execute(dict(action='rollback', operation='f'*32, restores='b'*32), root)['state'] == 'restore-unavailable'
    archive.write_bytes(intact)
    rollback = module.execute(dict(action='rollback', operation='e'*32, restores='b'*32), root)
    assert rollback['state'] == 'package-restored', rollback
    again = json.loads(subprocess.check_output(['claude', 'plugin', 'list', '--json'], cwd=root))
    assert next(p for p in again if p['id'] == entry['pluginId'])['version'] == '1.0.0'
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
