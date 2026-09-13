import hashlib
import importlib.util
import io
from pathlib import Path
import os
import tarfile
import tempfile

source = Path(__file__).resolve().parents[1] / 'android/desktop/src/main/resources/app/yxi/desktop/plugin-operation.py'
spec = importlib.util.spec_from_file_location('operation', source)
module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
with tempfile.TemporaryDirectory(prefix='yxi-package-') as temp:
    root = Path(temp); plugin = root / 'source'; plugin.mkdir()
    (plugin / 'file').write_text('old version'); (plugin / 'file').chmod(0o750)
    os.link(plugin / 'file', plugin / 'hard')
    os.symlink('file', plugin / 'soft')
    archive = root / 'backup.tar'
    digest = module.package_backup(plugin, archive)
    restored = module.unpack_package(archive, root / 'restored', digest)
    assert (restored / 'file').read_text() == 'old version'
    assert (restored / 'file').stat().st_mode & 0o777 == 0o750
    assert (restored / 'hard').stat().st_ino == (restored / 'file').stat().st_ino
    assert (restored / 'soft').is_symlink()
    try: module.unpack_package(archive, root / 'bad-hash', '0'*64)
    except ValueError: pass
    else: raise AssertionError('changed archive accepted')
    for index, name in enumerate(['plugin/../escape', '/escape', 'plugin/link/payload']):
        bad = root / ('bad-' + str(index) + '.tar')
        with tarfile.open(bad, 'w') as output:
            top = tarfile.TarInfo('plugin'); top.type = tarfile.DIRTYPE; output.addfile(top)
            link = tarfile.TarInfo('plugin/link'); link.type = tarfile.SYMTYPE; link.linkname = str(root); output.addfile(link)
            item = tarfile.TarInfo(name); item.size = 1; output.addfile(item, io.BytesIO(b'x'))
        try: module.unpack_package(bad, root / ('bad-output-' + str(index)), hashlib.sha256(bad.read_bytes()).hexdigest())
        except ValueError: pass
        else: raise AssertionError('escaping archive accepted')
    assert not (root / 'payload').exists()
print('package restore: files, modes, links, digest, traversal and linked parent checks passed')
