"""Exercise the exact desktop remote-save script against disposable files (no SSH or real workspace writes)."""
import base64
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest

SOURCE = Path(__file__).resolve().parents[1] / 'android/desktop/src/main/kotlin/app/yxi/desktop/FileDocument.kt'
SCRIPT = textwrap.dedent(SOURCE.read_text(encoding='utf-8').split('internal val SAVE_SCRIPT = """', 1)[1].split('""".trimIndent()', 1)[0]).strip()

def digest(data):
    return hashlib.sha256(data).hexdigest()

class DocumentSaveTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='yxi-document-save-')
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / "PRD '中文 $name.md"
        self.upload = Path(self.temp.name) / '.yxi-edit-test'
        self.path.write_bytes(b'original')
        self.path.chmod(0o640)
        self.upload.write_bytes(b'edited')

    def run_save(self, expected=None, wanted=None):
        args = {'path': str(self.path), 'temp': str(self.upload),
                'expected': expected or digest(b'original'), 'wanted': wanted or digest(b'edited')}
        result = subprocess.run(['python3', '-c', SCRIPT, base64.b64encode(json.dumps(args).encode()).decode()], capture_output=True, text=True, check=True)
        return json.loads(result.stdout)

    def test_save_preserves_mode_and_content(self):
        self.assertEqual('saved', self.run_save()['status'])
        self.assertEqual(b'edited', self.path.read_bytes())
        self.assertEqual(0o640, self.path.stat().st_mode & 0o777)
        self.assertFalse(self.upload.exists())

    def test_external_change_is_not_overwritten(self):
        self.path.write_bytes(b'other agent')
        self.assertEqual('conflict', self.run_save()['status'])
        self.assertEqual(b'other agent', self.path.read_bytes())
        self.assertEqual(b'edited', self.upload.read_bytes())

    def test_corrupt_upload_leaves_original_intact(self):
        self.upload.write_bytes(b'partial')
        self.assertEqual('error', self.run_save()['status'])
        self.assertEqual(b'original', self.path.read_bytes())

    def test_second_editor_stale_base_rejected(self):
        self.assertEqual('saved', self.run_save()['status'])
        self.upload.write_bytes(b'another edit')
        self.assertEqual('conflict', self.run_save(wanted=digest(b'another edit'))['status'])
        self.assertEqual(b'edited', self.path.read_bytes())

    def test_symlink_swap_is_rejected(self):
        other = self.path.with_name('other.md')
        other.write_bytes(b'original')
        self.path.unlink()
        self.path.symlink_to(other)
        self.assertEqual('conflict', self.run_save()['status'])
        self.assertEqual(b'original', other.read_bytes())

if __name__ == '__main__':
    unittest.main()
