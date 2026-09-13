import hashlib
import json
import os
import subprocess
import tempfile
from urllib.parse import urlsplit, urlunsplit

def catalog_entries(value):
    if not isinstance(value, dict) or not isinstance(value.get('available'), list):
        raise ValueError('unsupported catalog')
    entries = []
    for item in value['available']:
        if not isinstance(item, dict) or not isinstance(item.get('pluginId'), str) or not isinstance(item.get('marketplaceName'), str):
            raise ValueError('unsupported entry')
        def text(key):
            value = item.get(key, '')
            return value[:4096] if isinstance(value, str) else ''
        source = item.get('source')
        kind, location = 'unknown', ''
        if isinstance(source, str):
            kind = 'marketplace-path'
            # Only relative marketplace paths are displayed, never arbitrary URLs.
            if source.startswith('./'): location = source[:4096]
        elif isinstance(source, dict):
            kind = source.get('source') if source.get('source') in ('url', 'git-subdir', 'github', 'npm', 'pip', 'command') else 'unknown'
            url = source.get('url')
            if isinstance(url, str):
                parsed = urlsplit(url)
                if parsed.scheme in ('https', 'http') and parsed.hostname:
                    location = urlunsplit((parsed.scheme, parsed.hostname, parsed.path, '', ''))[:4096]
        entries.append(dict(id=text('pluginId'), name=text('name'), description=text('description'),
                            marketplace=text('marketplaceName'), version=text('version'), sourceKind=kind, location=location,
                            fingerprint=hashlib.sha256(json.dumps(item, sort_keys=True).encode()).hexdigest()))
    return entries

def load():
    try:
        with tempfile.TemporaryFile() as output, tempfile.TemporaryFile() as errors:
            result = subprocess.run(['claude', 'plugin', 'list', '--available', '--json'], cwd=os.path.expanduser('~'), stdin=subprocess.DEVNULL, stdout=output, stderr=errors, timeout=15)
            if result.returncode: return dict(state='error', entries=[])
            output.seek(0); data = output.read(8388609)
            if len(data) > 8388608: return dict(state='too-large', entries=[])
            return dict(state='ready', entries=catalog_entries(json.loads(data)))
    except FileNotFoundError: return dict(state='missing-runner', entries=[])
    except subprocess.TimeoutExpired: return dict(state='timeout', entries=[])
    except Exception: return dict(state='unrecognized', entries=[])

if __name__ == '__main__':
    print('__YXI_CATALOG__:' + json.dumps(load(), ensure_ascii=False))
