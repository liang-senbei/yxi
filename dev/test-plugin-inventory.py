import importlib.util
import json
from pathlib import Path
import tempfile

source = Path(__file__).resolve().parents[1] / 'android/desktop/src/main/resources/app/yxi/desktop/plugin-inventory.py'
spec = importlib.util.spec_from_file_location('inventory', source)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
with tempfile.TemporaryDirectory() as root:
    a, b = Path(root) / 'a', Path(root) / 'b'
    plugins = a / '.claude/plugins'
    plugins.mkdir(parents=True)
    installed = a / 'installed'
    installed.mkdir()
    registry = plugins / 'installed_plugins.json'
    registry.write_text(json.dumps({'plugins': {'sample@market': [{'version': '1.0', 'scope': 'project', 'projectPath': '/work/demo', 'installPath': str(installed), 'secret': 'NEVER_RETURN'}]}}))
    settings = a / '.claude/settings.json'
    settings.write_text(json.dumps({'enabledPlugins': {'sample@market': False}, 'env': {'TOKEN': 'NEVER_RETURN'}}))
    result = module.inventory(str(a))
    assert len(result['plugins']) == 1 and result['plugins'][0]['present']
    assert result['plugins'][0]['userEnabled'] is False
    assert result['plugins'][0]['project'] == '/work/demo'
    assert 'NEVER_RETURN' not in json.dumps(result)
    assert module.inventory(str(b))['plugins'] == []
    settings.write_text('{broken')
    assert module.inventory(str(a))['warnings']
    assert module.inventory(str(a))['plugins'][0]['userEnabled'] is None
    registry.write_text('{broken')
    assert module.inventory(str(a))['warnings']
    assert module.inventory(str(a))['plugins'] == []
print('plugin inventory: host isolation, scope, false/unknown, damaged records, no secrets passed')
