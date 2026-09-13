import tempfile, pathlib, json, subprocess, os
with tempfile.TemporaryDirectory(prefix='yxi-plugin-cli-') as root:
 p=pathlib.Path(root); c=p/'.claude'; d=p/'plugin'; (d/'.claude-plugin').mkdir(parents=True); (c/'plugins').mkdir(parents=True)
 (d/'.claude-plugin/plugin.json').write_text(json.dumps({'name':'yxi-fixture','version':'1.0.0','description':'isolated fixture'}))
 (c/'plugins/installed_plugins.json').write_text(json.dumps({'version':2,'plugins':{'yxi-fixture@fixture':[{'scope':'user','installPath':str(d),'version':'1.0.0','installedAt':'2026-09-13T00:00:00.000Z','lastUpdated':'2026-09-13T00:00:00.000Z'}]}}))
 (c/'settings.json').write_text(json.dumps({'enabledPlugins':{'yxi-fixture@fixture':True}}))
 env=dict(os.environ,HOME=root,CLAUDE_CONFIG_DIR=str(c))
 r=subprocess.run(['claude','plugin','list','--json'],cwd=root,env=env,capture_output=True,text=True,timeout=25)
 assert r.returncode == 0
 os.environ.update(HOME=root, CLAUDE_CONFIG_DIR=str(c))
 import importlib.util
 spec=importlib.util.spec_from_file_location('inventory',str(pathlib.Path(__file__).resolve().parents[1] / 'android/desktop/src/main/resources/app/yxi/desktop/plugin-inventory.py'))
 module=importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
 result=module.inventory(root)
 assert len(result['plugins'])==1, result
 assert result['plugins'][0]['runnerState']=='reported-error', result
 assert result['plugins'][0]['runnerEnabled'] is True, result
 print('real CLI fixture: listed plugin with marketplace error remains reported-error')


