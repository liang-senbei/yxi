import hashlib
import importlib.util
import json
import pathlib
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('release_validation', pathlib.Path(__file__).resolve().parents[1] / 'android/desktop/validate_release.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ReleaseValidationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='yxi-release-test-')
        self.root = pathlib.Path(self.temp.name)
        self.data = b'fixture-package'
        self.current = self.root / 'published.json'
        self.current.write_text(json.dumps(self.make_manifest('1.4.12')))
        (self.root / 'Yxi-win-Setup.exe').write_bytes(b'fixture-setup')

    def tearDown(self):
        self.temp.cleanup()

    def make_manifest(self, version):
        return {'Assets': [{'PackageId': 'Yxi', 'Type': 'Full', 'Version': version,
                            'FileName': f'Yxi-{version}-full.nupkg', 'Size': len(self.data),
                            'SHA256': hashlib.sha256(self.data).hexdigest()}]}

    def candidate(self, version):
        (self.root / f'Yxi-{version}-full.nupkg').write_bytes(self.data)
        (self.root / 'releases.win.json').write_text(json.dumps(self.make_manifest(version)))

    def test_new_verified_version(self):
        self.candidate('1.4.13')
        self.assertEqual('1.4.13', module.validate(self.root, self.current))

    def test_equal_and_older_versions_are_rejected(self):
        for version in ('1.4.12', '1.4.9'):
            self.candidate(version)
            with self.assertRaisesRegex(ValueError, 'overwrite'):
                module.validate(self.root, self.current)

    def test_corruption_even_when_size_matches(self):
        self.candidate('1.4.13')
        (self.root / 'Yxi-1.4.13-full.nupkg').write_bytes(b'short')
        with self.assertRaisesRegex(ValueError, 'size'):
            module.validate(self.root, self.current)
        (self.root / 'Yxi-1.4.13-full.nupkg').write_bytes(b'x' * len(self.data))
        with self.assertRaisesRegex(ValueError, 'SHA256'):
            module.validate(self.root, self.current)

    def test_unexpected_package_path_and_prerelease(self):
        self.candidate('1.4.13')
        value = self.make_manifest('1.4.13')
        value['Assets'][0]['FileName'] = '../other.nupkg'
        (self.root / 'releases.win.json').write_text(json.dumps(value))
        with self.assertRaisesRegex(ValueError, 'filename'):
            module.validate(self.root, self.current)
        self.candidate('1.4.13-beta')
        with self.assertRaisesRegex(ValueError, 'stable'):
            module.validate(self.root, self.current)


if __name__ == '__main__':
    unittest.main()
