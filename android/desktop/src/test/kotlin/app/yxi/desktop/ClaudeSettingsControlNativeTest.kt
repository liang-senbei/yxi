package app.yxi.desktop

import java.io.File
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import kotlin.test.*

class ClaudeSettingsControlNativeTest {
    @Test fun `native Claude reports whether effective settings are available before prompts`() {
        check(!System.getenv("YXI_ISOLATED_TEST_RUN").isNullOrBlank() && File("/.dockerenv").exists())
        check(File("/sys/class/net").list()?.toSet() == setOf("lo"))
        val root = File("/sandbox/tmp/claude-settings-control").apply { mkdirs() }
        val stub = File(root, "server.py").apply { writeText(ClaudeSettingsControlNativeTest::class.java.getResource("/rewind/anthropic_stub.py")!!.readText()) }
        val server = ProcessBuilder("python3", stub.path, root.path).redirectErrorStream(true).redirectOutput(File(root, "server.log")).start()
        val script = File(root, "probe.py").apply { writeText("""
import json,os,pathlib,select,subprocess,sys,time
root=pathlib.Path(sys.argv[1]); home=root/'home'; home.mkdir()
deadline=time.monotonic()+5
while not (root/'port').exists() and time.monotonic()<deadline: time.sleep(0.02)
settings=json.loads(sys.argv[2]); settings['env']['ANTHROPIC_BASE_URL']='http://127.0.0.1:'+(root/'port').read_text().strip()
env={'HOME':str(home),'PATH':'/usr/bin:/bin','CLAUDE_CONFIG_DIR':str(home/'.claude'),
 'CLAUDE_CODE_OAUTH_TOKEN':'synthetic-control-token','DISABLE_AUTOUPDATER':'1','CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC':'1'}
errors=open('/results/claude-control-native-error.txt','wb')
process=subprocess.Popen(['/opt/native/claude','--settings',json.dumps(settings),'-p','--input-format','stream-json','--output-format','stream-json','--verbose'],
 cwd=home,env=env,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=errors,start_new_session=True)
buffer=b''
def exchange(kind,request_id):
 global buffer
 process.stdin.write((json.dumps({'type':'control_request','request_id':request_id,'request':{'subtype':kind}})+'\n').encode()); process.stdin.flush()
 deadline=time.monotonic()+15
 while time.monotonic()<deadline:
  while b'\n' in buffer:
   line,buffer=buffer.split(b'\n',1)
   if not line: continue
   value=json.loads(line)
   with open('/results/claude-control-frames.jsonl','a') as log: log.write(json.dumps(value)+'\n')
   if value.get('type')=='control_response' and value.get('response',{}).get('request_id')==request_id: return value
  if select.select([process.stdout],[],[],0.1)[0]:
   chunk=os.read(process.stdout.fileno(),65536)
   if not chunk: raise RuntimeError('Native control stream ended')
   buffer+=chunk
   if len(buffer)>1048576: raise RuntimeError('Native control response exceeded bound')
 raise RuntimeError('Native control response timed out')
try:
 initial=exchange('initialize','init')
 pathlib.Path('/results/claude-control-initialize.json').write_text(json.dumps(initial,indent=2))
 assert initial['response']['subtype']=='success'
 result=exchange('get_settings','settings')
 pathlib.Path('/results/claude-control-settings.json').write_text(json.dumps(result,indent=2))
finally:
 import signal
 if process.poll() is None:
  os.killpg(process.pid,signal.SIGTERM)
  try: process.wait(timeout=3)
  except subprocess.TimeoutExpired:
   os.killpg(process.pid,signal.SIGKILL); process.wait()
 errors.close()
""".trimIndent()) }
        val process = ProcessBuilder("python3", script.path, root.path, ClaudeSubscriptionSettings.overlay().toString())
            .redirectErrorStream(true).redirectOutput(File("/results/claude-control-probe.log")).start()
        try {
            check(process.waitFor(40, TimeUnit.SECONDS)) { "Native settings probe timed out" }
            assertEquals(0, process.exitValue())
            val response = JSONObject(File("/results/claude-control-settings.json").readText()).getJSONObject("response")
            assertEquals("settings", response.getString("request_id"))
            assertTrue(response.getString("subtype") in setOf("success", "error"))
            val requests = File(root, "requests.jsonl")
            if (requests.exists()) assertTrue(requests.readLines().map(::JSONObject).all { it.getJSONArray("messages").length() == 0 }, "Control inspection must not send model messages")
        } finally { LocalRuntimeDiscovery.stopOwnedProcess(process); server.destroyForcibly(); server.waitFor(5, TimeUnit.SECONDS) }
    }
}
