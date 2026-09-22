package app.yxi.desktop

import app.yxi.agent.RewindLiveVerification
import app.yxi.ssh.Shell
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64

/** Guarded menu steps only. Submission of edited content is deliberately a separate operation. */
internal object NativeFirstTurnKeys {
    const val TAG = "__YXI_NATIVE_KEYS__"
    enum class Action { Open, Up, Enter, Clear, Cancel }

    fun fingerprintCommand(source: RewindTarget): String {
        require(source.size in 0..1024L * 1024 * 1024)
        val code = """
import hashlib,os,sys
path,size,modified=sys.argv[1:]
with open(path,'rb') as f:
 before=os.fstat(f.fileno())
 if before.st_size!=int(size) or before.st_mtime_ns!=int(modified): raise ValueError('History changed')
 digest=hashlib.sha256()
 for data in iter(lambda:f.read(1024*1024),b''): digest.update(data)
 after=os.fstat(f.fileno()); current=os.stat(path)
 identity=lambda s:(s.st_dev,s.st_ino,s.st_size,s.st_mtime_ns)
 if identity(before)!=identity(after) or identity(before)!=identity(current): raise ValueError('History changed')
print(digest.hexdigest())
""".trimIndent()
        return "timeout 30s python3 -c ${Shell.q(code)} ${Shell.q(source.file)} ${source.size} ${Shell.q(source.modifiedNs)}"
    }

    fun command(identity: RewindLiveVerification.RuntimeIdentity, source: RewindTarget, sourceSha256: String, screen: String,
        action: Action, count: Int = 1): String {
        RewindLiveVerification.registrationCommand(identity, sameProcess = true) // validate identity fields
        require(source.sessionId == identity.sessionId && source.parentUuid == null)
        require(count in 1..2000 && (action == Action.Up || count == 1))
        require(source.size >= 0 && source.modifiedNs.toLongOrNull() != null)
        require(Regex("^[0-9a-f]{64}$").matches(sourceSha256))
        val digest = MessageDigest.getInstance("SHA-256").digest(screen.trimEnd('\n').toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val packet = JSONObject().put("session", identity.sessionName).put("runtime", identity.runtimeId)
            .put("pane", identity.paneId).put("pid", identity.pid).put("exe", identity.exe).put("sid", identity.sessionId)
            .put("file", source.file).put("size", source.size).put("historyHash", sourceSha256)
            .put("screen", digest).put("action", action.name).put("count", count)
        val encoded = Base64.getEncoder().encodeToString(packet.toString().toByteArray(Charsets.UTF_8))
        return "python3 -c ${Shell.q(script)} ${Shell.q(encoded)}"
    }

    fun sent(output: String) = output.trim() == "$TAG:sent"

    private val script = """
import base64,hashlib,json,os,subprocess,sys
p=json.loads(base64.b64decode(sys.argv[1]))
def reject(reason):
 print('__YXI_NATIVE_KEYS__:'+reason); raise SystemExit
def tmux(*args):
 r=subprocess.run(['tmux',*args],capture_output=True)
 if r.returncode: reject('tmux')
 return r.stdout
target='='+p['session']+':'
def info(fmt): return tmux('display-message','-p','-t',target,fmt).decode().strip()
if info('#{pid}:#{session_id}:#{session_created}')!=p['runtime'] or info('#{pane_id}')!=p['pane']: reject('identity')
pane_pid=info('#{pane_pid}'); pid=p['pid']
try:
 if os.path.realpath(os.readlink('/proc/'+pid+'/exe'))!=os.path.realpath(p['exe']): reject('process')
 current=pid; belongs=current==pane_pid
 for _ in range(16):
  if belongs: break
  stat=open('/proc/'+current+'/stat').read(); current=stat[stat.rindex(')')+2:].split()[1]
  belongs=current==pane_pid
 if not belongs: reject('process')
except OSError: reject('process')
full=info('#{session_name}:#{window_id}.#{pane_id}')
records=[]
directory=os.path.expanduser('~/.claude/sessions')
try:
 for name in os.listdir(directory):
  if not name.endswith('.json'): continue
  path=os.path.join(directory,name)
  try:
   with open(path) as f: record=json.load(f)
   if str(record.get('pid'))==pid: records.append((os.path.getmtime(path),record))
  except (OSError,ValueError): pass
except OSError: reject('registration')
if not records: reject('registration')
record=max(records,key=lambda item:item[0])[1]
if record.get('sessionId')!=p['sid'] or record.get('tmux')!=full or record.get('kind')!='interactive': reject('registration')
try:
 with open(p['file'],'rb') as f:
  before=os.fstat(f.fileno())
  if before.st_size!=p['size']: reject('history')
  digest=hashlib.sha256()
  for data in iter(lambda:f.read(1024*1024),b''): digest.update(data)
  after=os.fstat(f.fileno()); current=os.stat(p['file'])
  identity=lambda s:(s.st_dev,s.st_ino,s.st_size,s.st_mtime_ns)
  if identity(before)!=identity(after) or identity(before)!=identity(current) or digest.hexdigest()!=p['historyHash']: reject('history')
except OSError: reject('history')
screen=tmux('capture-pane','-p','-t',p['pane']).rstrip(b'\n')
if hashlib.sha256(screen).hexdigest()!=p['screen']: reject('screen')
action=p['action']; count=p['count']
if not isinstance(count,int) or not 1<=count<=2000: reject('action')
if action=='Open':
 tmux('send-keys','-t',p['pane'],'-l','--','/rewind')
 tmux('send-keys','-t',p['pane'],'Enter')
elif action=='Up': tmux('send-keys','-t',p['pane'],*(['Up']*count))
elif action in ('Enter','Clear','Cancel'):
 tmux('send-keys','-t',p['pane'],{'Enter':'Enter','Clear':'C-u','Cancel':'Escape'}[action])
else: reject('action')
print('__YXI_NATIVE_KEYS__:sent')
""".trimIndent()
}
