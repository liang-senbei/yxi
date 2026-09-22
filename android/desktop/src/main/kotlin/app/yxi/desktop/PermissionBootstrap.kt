package app.yxi.desktop

import app.yxi.agent.*
import app.yxi.ssh.Shell
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest

/** Explicit one-click setup: restart only an idle, identity-bound native conversation. */
internal suspend fun configureConversationBypass(conn: Conn, session: Session) {
    conn.instructionDeliveryMutex.withLock {
        check(!session.isCodex && session.runtimeId.isNotBlank() && conn.ssh.isConnected)
        check(!RewindDelivery.gate.blocked(taskNavigationKey(conn.host, session))) { "请先完成回退核验" }
        val screen = conn.ssh.exec("tmux capture-pane -p -t ${Shell.q("=" + session.name + ":")}")
        check(Model.borrowable(screen) && Prompt.parse(screen) == null) { "请等待当前任务结束，并处理终端里的未发送内容" }
        val cap = (Rewind.parseCapture(conn.ssh.exec(Rewind.captureCommand(session.name))) as? Rewind.Got)?.capture
            ?: error("无法确认当前 Claude 进程，未执行重启")
        try {
            val hash = MessageDigest.getInstance("SHA-256").digest(screen.trimEnd('\n').toByteArray()).joinToString("") { "%02x".format(it) }
            val args = listOf(session.name, session.runtimeId, cap.paneId, cap.pid, cap.exe, hash)
            val response = conn.ssh.exec("python3 -c ${Shell.q(permissionBootstrapScript)} " + args.joinToString(" ", transform = Shell::q))
            val sid = response.lineSequence().lastOrNull { it.startsWith("__YXI_PERMISSION_RESTART__:") }?.substringAfter(':')
            check(sid != null && Regex("[0-9a-fA-F-]{36}").matches(sid)) { "配置未完成：会话身份、启动参数或状态无法确认。请查看终端；不会修改全局权限。" }
            confirmExplicitBypass(conn, session)
            val identity = RewindLiveVerification.RuntimeIdentity(session.name, session.runtimeId, cap.paneId, cap.exe, cap.pid, sid, 0.0)
            val proof = conn.ssh.exec(RewindLiveVerification.registrationCommand(identity))
            check(proof.startsWith("READY")) { "运行器已重启，但原会话身份尚未确认，请查看终端" }
        } finally {
            runCatching { conn.ssh.exec(Rewind.cleanupCommand(cap)) }
        }
    }
}

internal val permissionBootstrapScript = """
import hashlib,json,os,pathlib,shlex,subprocess,sys,tempfile,time
name,runtime,pane,pid,exe,expected=sys.argv[1:]
def tm(*args): return subprocess.check_output(['tmux',*args],text=True).rstrip('\n')
def identity():
 if tm('display-message','-p','-t',pane,'#{pid}:#{session_id}:#{session_created}')!=runtime: raise ValueError('instance changed')
 if tm('display-message','-p','-t','='+name+':','#{pane_id}')!=pane: raise ValueError('pane changed')
 if os.path.realpath('/proc/'+pid+'/exe')!=exe: raise ValueError('process changed')
 if hashlib.sha256(tm('capture-pane','-p','-t',pane).encode()).hexdigest()!=expected: raise ValueError('screen changed')
identity()
root=pathlib.Path('/proc')/pid
environment=dict(x.split('=',1) for x in (root/'environ').read_bytes().decode().split('\0') if '=' in x)
registry=pathlib.Path(environment.get('CLAUDE_CONFIG_DIR',str(pathlib.Path(environment['HOME'])/'.claude')))/'sessions'
records=[]
for path in registry.glob('*.json'):
 try: item=json.loads(path.read_text())
 except (ValueError,OSError): continue
 if str(item.get('pid'))==pid and str(item.get('tmux','')).startswith(name+':'): records.append(item)
if len(records)!=1 or records[0].get('status')!='idle': raise ValueError('runner not idle')
sid=records[0]['sessionId']
import re
if not re.fullmatch('[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}',sid): raise ValueError('invalid session')
argv=(root/'cmdline').read_bytes().decode().split('\0');argv=[x for x in argv if x]
retained=[];i=1
while i<len(argv):
 arg=argv[i]
 if arg in ('--resume','-r','--permission-mode','--model','--effort','--name'):
  if i+1>=len(argv): raise ValueError('incomplete flag')
  value=argv[i+1]
  if arg in ('--resume','-r') and value!=sid: raise ValueError('resume mismatch')
  if arg in ('--model','--effort','--name'): retained.extend([arg,value])
  i+=2
 elif arg in ('--dangerously-skip-permissions','--allow-dangerously-skip-permissions'): i+=1
 elif arg=='--' and i+2==len(argv):
  # The initial prompt is already in the resumed history. Never submit it twice.
  break
 else: raise ValueError('unsupported launch option')
cwd=os.readlink(root/'cwd')
environment['IS_SANDBOX']='1'
payload={'exe':exe,'argv':[exe,*retained,'--resume',sid,'--permission-mode','bypassPermissions'],'env':environment,'cwd':cwd}
fd,path=tempfile.mkstemp(prefix='yxi-permission-',suffix='.json')
try:
 with os.fdopen(fd,'w') as out:
  json.dump(payload,out);out.flush();os.fsync(out.fileno())
 identity()
 worker="import json,os,sys; p=sys.argv[1]; d=json.load(open(p)); os.unlink(p); os.chdir(d['cwd']); os.execve(d['exe'],d['argv'],d['env'])"
 command=shlex.join([sys.executable,'-c',worker,path])
 subprocess.run(['tmux','respawn-pane','-k','-t',pane,'-c',cwd,command],check=True)
 for _ in range(100):
  if not os.path.exists(path):break
  time.sleep(.1)
 else:raise ValueError('restart worker did not consume context')
 print('__YXI_PERMISSION_RESTART__:'+sid)
except BaseException:
 if os.path.exists(path):os.unlink(path)
 raise
""".trimIndent()
