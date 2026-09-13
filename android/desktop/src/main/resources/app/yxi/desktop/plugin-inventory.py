import json
import os

# Read only an explicit set of installation fields; never return configuration values.
def inventory(home):
    warnings = []
    def read(path):
        try:
            with open(path, encoding='utf-8') as f:
                raw = f.read(4 * 1024 * 1024 + 1)
            if len(raw) > 4 * 1024 * 1024:
                raise ValueError()
            value = json.loads(raw)
            if not isinstance(value, dict):
                raise ValueError()
            return value
        except FileNotFoundError:
            return {}
        except Exception:
            warnings.append('无法读取 ' + os.path.basename(path))
            return {}
    base = os.path.join(home, '.claude')
    registry = read(os.path.join(base, 'plugins', 'installed_plugins.json'))
    settings = read(os.path.join(base, 'settings.json'))
    enabled = settings.get('enabledPlugins', {})
    if not isinstance(enabled, dict):
        warnings.append('enabledPlugins 格式无法识别')
        enabled = {}
    entries = registry.get('plugins', {})
    if not isinstance(entries, dict):
        warnings.append('插件安装记录格式无法识别')
        entries = {}
    plugins = []
    def field(value):
        return value[:4096] if isinstance(value, str) else ''
    for name, installs in entries.items():
        if not isinstance(installs, list):
            warnings.append('某项安装记录格式无法识别')
            continue
        for entry in installs:
            if not isinstance(entry, dict):
                warnings.append('某项安装记录格式无法识别')
                continue
            path = field(entry.get('installPath'))
            plugins.append(dict(id=field(name), version=field(entry.get('version')),
                                scope=field(entry.get('scope')), project=field(entry.get('projectPath')),
                                path=path, present=bool(path and os.path.isabs(path) and os.path.isdir(path)),
                                userEnabled=enabled.get(name) if type(enabled.get(name)) is bool else None))
    return dict(plugins=plugins, warnings=sorted(set(warnings)), runner='claude')

if __name__ == '__main__':
    print('__YXI_PLUGINS__:' + json.dumps(inventory(os.path.expanduser('~')), ensure_ascii=False))
