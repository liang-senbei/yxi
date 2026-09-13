import json
import os
import subprocess
import tempfile

def runner_inventory(home):
    # Fixed argv, no install or startup of an agent. Do not return CLI diagnostics,
    # which can contain local configuration values.
    try:
        with tempfile.TemporaryFile() as output, tempfile.TemporaryFile() as errors:
            result = subprocess.run(['claude', 'plugin', 'list', '--json'], cwd=home,
                                    stdin=subprocess.DEVNULL, stdout=output, stderr=errors, timeout=12)
            if result.returncode != 0:
                return None, '运行器插件查询失败'
            output.seek(0)
            raw = output.read(4 * 1024 * 1024 + 1)
            if len(raw) > 4 * 1024 * 1024:
                return None, '运行器插件列表过大'
            items = json.loads(raw)
            if not isinstance(items, list) or any(not isinstance(p, dict) or not isinstance(p.get('id'), str) or not isinstance(p.get('scope'), str) or not isinstance(p.get('installPath'), str) for p in items):
                return None, '运行器插件格式无法识别'
            return items, ''
    except FileNotFoundError:
        return None, '未找到 Claude 运行器'
    except subprocess.TimeoutExpired:
        return None, '运行器插件查询超时'
    except Exception:
        return None, '无法读取运行器插件状态'

# Read only an explicit set of installation fields; never return configuration values.
def inventory(home, runner_query=runner_inventory):
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
    runtime, runtime_warning = runner_query(home)
    if runtime_warning:
        warnings.append(runtime_warning)
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
            matches = [] if runtime is None else [p for p in runtime if p.get('id') == name and p.get('scope') == entry.get('scope') and p.get('installPath') == path and p.get('projectPath', '') == entry.get('projectPath', '')]
            actual = matches[0] if len(matches) == 1 else None
            state = 'unknown' if runtime is None or len(matches) > 1 else 'unrecognized' if actual is None else 'reported-error' if actual.get('errors') else 'listed'
            plugins.append(dict(id=field(name), version=field(entry.get('version')),
                                scope=field(entry.get('scope')), project=field(entry.get('projectPath')),
                                path=path, present=bool(path and os.path.isabs(path) and os.path.isdir(path)),
                                userEnabled=enabled.get(name) if type(enabled.get(name)) is bool else None,
                                runnerState=state,
                                runnerEnabled=actual.get('enabled') if actual is not None and type(actual.get('enabled')) is bool else None))
    return dict(plugins=plugins, warnings=sorted(set(warnings)), runner='claude')

if __name__ == '__main__':
    print('__YXI_PLUGINS__:' + json.dumps(inventory(os.path.expanduser('~')), ensure_ascii=False))
