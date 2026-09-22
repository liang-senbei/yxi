"""Validate the current stable Windows release contract before any remote upload."""
import hashlib
import json
import pathlib
import re
import sys


def manifest(path):
    path = pathlib.Path(path)
    if path.stat().st_size > 1024 * 1024:
        raise ValueError('Release manifest is too large')
    value = json.loads(path.read_text(encoding='utf-8-sig'))
    if not isinstance(value, dict):
        raise ValueError('Invalid release manifest object')
    assets = value.get('Assets')
    if not isinstance(assets, list) or len(assets) != 1:
        raise ValueError('Expected one fresh full Windows package')
    asset = assets[0]
    if not isinstance(asset, dict):
        raise ValueError('Invalid release asset object')
    if asset.get('PackageId') != 'Yxi' or asset.get('Type') != 'Full':
        raise ValueError('Unexpected package identity or type')
    version = asset.get('Version', '')
    if not isinstance(version, str) or not re.fullmatch(r'(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)', version):
        raise ValueError('Only explicit stable three-part versions may be published')
    if asset.get('FileName') != f'Yxi-{version}-full.nupkg':
        raise ValueError('Unexpected package filename')
    if type(asset.get('Size')) is not int or asset['Size'] <= 0:
        raise ValueError('Invalid package size')
    if not isinstance(asset.get('SHA256'), str) or not re.fullmatch(r'[0-9a-fA-F]{64}', asset['SHA256']):
        raise ValueError('Missing or invalid SHA256')
    return asset, tuple(map(int, version.split('.')))


def validate(directory, published_manifest):
    directory = pathlib.Path(directory)
    candidate, version = manifest(directory / 'releases.win.json')
    published, current = manifest(published_manifest)
    if version <= current:
        raise ValueError(f"Refusing to overwrite published {published['Version']} with {candidate['Version']}")
    package = directory / candidate['FileName']
    if package.is_symlink() or not package.is_file() or package.stat().st_size != candidate['Size']:
        raise ValueError('Package missing, linked, or size does not match manifest')
    digest = hashlib.sha256()
    with package.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    if digest.hexdigest().lower() != candidate['SHA256'].lower():
        raise ValueError('Package SHA256 does not match manifest')
    setup = directory / 'Yxi-win-Setup.exe'
    if setup.is_symlink() or not setup.is_file() or setup.stat().st_size == 0:
        raise ValueError('Setup executable missing or linked')
    return candidate['Version']


if __name__ == '__main__':
    try:
        print('Validated stable release ' + validate(sys.argv[1], sys.argv[2]))
    except (ValueError, OSError, KeyError, TypeError, IndexError) as error:
        sys.exit('Release rejected: ' + str(error))
