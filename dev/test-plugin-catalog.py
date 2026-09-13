import importlib.util
from pathlib import Path
import json
source = Path(__file__).resolve().parents[1] / 'android/desktop/src/main/resources/app/yxi/desktop/plugin-catalog.py'
spec = importlib.util.spec_from_file_location('catalog', source)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
entry = {'pluginId': 'sample@market', 'name': 'sample', 'marketplaceName': 'market', 'description': 'Description', 'source': {'source': 'url', 'url': 'https://user:PRIVATE@example.com/plugin.git?token=PRIVATE#PRIVATE', 'headers': {'Authorization': 'PRIVATE'}}}
result = module.catalog_entries({'available': [entry]})
assert result[0]['location'] == 'https://example.com/plugin.git'
assert 'PRIVATE' not in json.dumps(result)
assert result[0]['version'] == ''
first = result[0]['fingerprint']
entry['description'] = 'Changed'
assert module.catalog_entries({'available': [entry]})[0]['fingerprint'] != first
for invalid in [[], {}, {'available': [None]}, {'available': [{'pluginId': 'x'}]}]:
    try: module.catalog_entries(invalid)
    except ValueError: pass
    else: raise AssertionError('invalid catalog accepted')
print('catalog metadata: source redaction, unknown version, entry fingerprint, malformed response passed')
