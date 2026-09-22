import importlib.util
import http.server
import json
import sys
import threading
import unittest

spec = importlib.util.spec_from_file_location('discovery', sys.argv.pop(1))
discovery = importlib.util.module_from_spec(spec)
spec.loader.exec_module(discovery)

class DiscoveryTests(unittest.TestCase):
    def test_candidates(self):
        self.assertEqual(discovery.candidates('https://example.test/v1/'), ['https://example.test/v1/models'])
        self.assertEqual(discovery.candidates('https://example.test/api/paas/v4'),
                         ['https://example.test/api/paas/v4/models', 'https://example.test/api/paas/v4/v1/models'])
        self.assertEqual(discovery.candidates('https://example.test/api/anthropic'),
                         ['https://example.test/api/anthropic/v1/models', 'https://example.test/v1/models', 'https://example.test/models'])
        self.assertEqual(discovery.candidates('https://example.test/v1/chat/completions'), ['https://example.test/v1/models'])
        self.assertEqual(discovery.candidates('https://example.test/api/v4/chat/completions'), ['https://example.test/api/v4/models'])
        self.assertEqual(discovery.candidates('https://example.test', 'https://example.test/catalog'), ['https://example.test/catalog'])
        for url in ('file:///tmp/key', 'https://user:password@example.test', 'https://example.test/#token'):
            with self.assertRaises(ValueError): discovery.candidates(url)

    def setUp(self):
        self.calls = []
        calls = self.calls
        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args): pass
            def do_GET(self):
                calls.append((self.path, self.headers.get('Authorization') == 'Bearer fixture-key-model-discovery'))
                status = 200
                if self.path == '/v1/models': data = {'data': [{'id':'z-model','owned_by':'fixture'}, {'id':'a-model'}, {'id':'z-model'}]}
                elif self.path == '/glm/models': data = {'models':[{'slug':'glm-example'}]}
                elif self.path == '/empty/models': data = {'data':[]}
                elif self.path == '/auth/models': status = 403; data = {'error':'fixture-key-model-discovery must never be echoed'}
                elif self.path == '/redirect/models':
                    self.send_response(302); self.send_header('Location','/v1/models'); self.end_headers(); return
                elif self.path == '/invalid/models': data = {'html':'not a model catalog'}
                else: status = 404; data = {'error':'missing'}
                raw = json.dumps(data).encode()
                self.send_response(status); self.send_header('Content-Type','application/json')
                self.send_header('Content-Length', str(len(raw))); self.end_headers(); self.wfile.write(raw)
        self.server = http.server.ThreadingHTTPServer(('127.0.0.1',0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True); self.thread.start()
        self.base = 'http://127.0.0.1:' + str(self.server.server_port)

    def tearDown(self):
        self.server.shutdown(); self.server.server_close(); self.thread.join(2)

    def fetch(self, path='', override=''):
        return discovery.fetch({'baseUrl':self.base+path,'apiKey':'fixture-key-model-discovery','modelsUrl':override})

    def test_compatibility_fallback_and_dedup(self):
        result = self.fetch('/api/anthropic')
        self.assertTrue(result['ok']); self.assertEqual([m['id'] for m in result['models']], ['a-model','z-model'])
        self.assertEqual(self.calls, [('/api/anthropic/v1/models',True),('/v1/models',True)])

    def test_explicit_url_and_glm_shape(self):
        result = self.fetch(override=self.base+'/glm/models')
        self.assertEqual(result['models'], [{'id':'glm-example','owner':None}])
        self.assertEqual(len(self.calls), 1)

    def test_auth_failure_is_sanitized_and_not_retried(self):
        result = self.fetch(override=self.base+'/auth/models')
        self.assertEqual(result['error'], 'auth'); self.assertEqual(len(self.calls), 1)
        self.assertNotIn('fixture-key-model-discovery', json.dumps(result))

    def test_redirect_does_not_forward_credentials(self):
        result = self.fetch(override=self.base+'/redirect/models')
        self.assertEqual(result['error'], 'redirect'); self.assertEqual(self.calls, [('/redirect/models',True)])

    def test_empty_and_invalid_are_distinct(self):
        self.assertEqual(self.fetch(override=self.base+'/empty/models'), {'ok':True,'models':[]})
        self.assertEqual(self.fetch(override=self.base+'/invalid/models')['error'], 'format')

if __name__ == '__main__': unittest.main()
