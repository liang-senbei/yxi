"""Provider model discovery. Endpoint strategy adapted from CC Switch (MIT, Jason Young).
Reference: farion1231/cc-switch @ 56df6513943062e8ca9eb80d8928eef7cf08a76d.
Credentials arrive only on stdin; responses/errors never include headers or response bodies.
"""
import json
import re
import socket
import sys
import urllib.error
import urllib.parse
import urllib.request

SUFFIXES = ('/api/claudecode', '/api/anthropic', '/apps/anthropic', '/api/coding',
            '/claudecode', '/anthropic', '/step_plan', '/coding', '/claude')

def valid_url(value):
    u = urllib.parse.urlsplit(value)
    if u.scheme not in ('https', 'http') or not u.hostname or u.username or u.password or u.fragment:
        raise ValueError('url')
    if any(ord(c) < 33 for c in value): raise ValueError('url')
    _ = u.port
    return value

def candidates(base, override=''):
    base = valid_url(base.strip().rstrip('/'))
    if override.strip(): return [valid_url(override.strip())]
    u = urllib.parse.urlsplit(base)
    if u.query: raise ValueError('url')
    path = u.path.rstrip('/')
    def at(p): return urllib.parse.urlunsplit((u.scheme, u.netloc, p, '', ''))
    if path.endswith('/models'): return [base]
    if any(path.endswith(s) for s in ('/messages', '/chat/completions', '/responses')):
        if '/v1/' in path: return [at(path.split('/v1/', 1)[0] + '/v1/models')]
        return [at(path.rsplit('/', 1)[0] + '/v1/models')]
    urls = [at(path + '/models')] if re.search(r'/v\d+$', path) else [at(path + '/v1/models')]
    if re.search(r'/v\d+$', path) and not path.endswith('/v1'): urls.append(at(path + '/v1/models'))
    for suffix in SUFFIXES:
        if path.endswith(suffix):
            root = path[:-len(suffix)]
            urls.extend([at(root + '/v1/models'), at(root + '/models')])
            break
    # Match the upstream preset override only for its exact default endpoint.
    if base == 'https://api.deepseek.com/anthropic': urls.insert(0, 'https://api.deepseek.com/models')
    return list(dict.fromkeys(urls))

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs): return None

def fetch(request):
    key = request.get('apiKey', '').strip()
    if not key: return {'ok': False, 'error': 'missing-key'}
    if len(key) > 16384 or any(ord(c) < 32 or ord(c) == 127 for c in key): return {'ok': False, 'error': 'key-format'}
    try: urls = candidates(request.get('baseUrl', ''), request.get('modelsUrl', ''))
    except (ValueError, TypeError): return {'ok': False, 'error': 'url'}
    headers = {'Accept': 'application/json', 'Accept-Encoding': 'identity', 'User-Agent': 'Yxi-model-discovery/1'}
    if urllib.parse.urlsplit(request['baseUrl']).hostname == 'api.anthropic.com':
        headers.update({'x-api-key': key, 'anthropic-version': '2023-06-01'})
    else: headers['Authorization'] = 'Bearer ' + key
    opener = urllib.request.build_opener(NoRedirect())
    for url in urls:
        try:
            with opener.open(urllib.request.Request(url, headers=headers), timeout=15) as response:
                raw = response.read(2 * 1024 * 1024 + 1)
                if len(raw) > 2 * 1024 * 1024: return {'ok': False, 'error': 'too-large'}
                data = json.loads(raw)
            if not isinstance(data, dict): raise ValueError('shape')
            entries = data.get('data') if data.get('data') is not None else data.get('models')
            if not isinstance(entries, list): raise ValueError('shape')
            if len(entries) > 10000: return {'ok': False, 'error': 'too-large'}
            models = {}
            for item in entries:
                if not isinstance(item, dict): raise ValueError('shape')
                ident = item.get('id', item.get('slug'))
                if not isinstance(ident, str) or not ident.strip(): raise ValueError('shape')
                ident = ident.strip()
                if len(ident) > 512 or any(ord(c) < 32 or ord(c) == 127 for c in ident) or key in ident: continue
                owner = item.get('owned_by')
                if not isinstance(owner, str) or key in owner: owner = None
                models.setdefault(ident, {'id': ident, 'owner': owner[:256] if owner else None})
            return {'ok': True, 'models': [models[k] for k in sorted(models)]}
        except urllib.error.HTTPError as e:
            if e.code in (404, 405): continue
            return {'ok': False, 'error': 'auth' if e.code in (401, 403) else 'redirect' if 300 <= e.code < 400 else 'http', 'status': e.code}
        except (TimeoutError, socket.timeout): return {'ok': False, 'error': 'timeout'}
        except urllib.error.URLError as e:
            return {'ok': False, 'error': 'timeout' if isinstance(e.reason, (TimeoutError, socket.timeout)) else 'network'}
        except (ValueError, UnicodeError, TypeError): return {'ok': False, 'error': 'format'}
    return {'ok': False, 'error': 'not-found'}

if __name__ == '__main__':
    try:
        request = json.loads(sys.stdin.buffer.read(65537))
        result = fetch(request)
    except Exception:
        result = {'ok': False, 'error': 'request'}
    print(json.dumps(result, ensure_ascii=False))
