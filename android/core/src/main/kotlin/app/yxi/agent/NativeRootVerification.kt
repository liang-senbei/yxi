package app.yxi.agent

import app.yxi.ssh.Shell

/** Read-only proof for an in-process first-turn restore; separate from restart/anchor verification. */
object NativeRootVerification {
    const val TAG = "__YXI_REWIND_ROOT__"
    data class Query(val runtime: RewindLiveVerification.RuntimeIdentity, val transcriptPath: String,
        val originalMessageUuid: String, val editedTextSha256: String)

    fun requireValid(query: Query) {
        require(Regex("^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$").matches(query.originalMessageUuid))
        require(Regex("^[0-9a-f]{64}$").matches(query.editedTextSha256))
        require(query.transcriptPath.startsWith('/') && query.transcriptPath.endsWith("/${query.runtime.sessionId}.jsonl"))
        require(query.transcriptPath.length <= 2048 && query.transcriptPath.none { it == '\u0000' || it == '\n' || it == '\r' })
        RewindLiveVerification.registrationCommand(query.runtime, sameProcess = true)
    }

    fun command(query: Query, registrationTimeoutSec: Int = 20): String {
        requireValid(query)
        require(registrationTimeoutSec in 1..600)
        val reg = RewindLiveVerification.registrationCommand(query.runtime, sameProcess = true)
        val chain = "python3 -c ${Shell.q(chainScript)} ${Shell.q(query.transcriptPath)} " +
            "${Shell.q(query.originalMessageUuid)} ${Shell.q(query.editedTextSha256)} allow-idle-user"
        val body = "deadline=\$((\$(date +%s) + $registrationTimeoutSec)); c=pending; " +
            "while :; do r=\$($reg) || r=probe-fail; " +
            "case \"\$r\" in identity|sid-mismatch) break;; " +
            "READY*) c=\$($chain) || c=chain-fail; case \"\$c\" in ok|idle-user) break;; esac;; esac; " +
            "[ \$(date +%s) -lt \$deadline ] || break; sleep 2; done; " +
            "case \"\$r\" in READY*) c=\$($chain) || c=chain-fail; " +
            "if [ \"\$c\" = ok ] || [ \"\$c\" = idle-user ]; then r2=\$($reg) || r2=probe-fail; " +
            "if [ \"\$r\" = \"\$r2\" ]; then printf '$TAG:%s\\n' \"\$c\"; " +
            "elif [ \"\$r2\" = not-ready:status ]; then echo '$TAG:became-busy'; else echo '$TAG:process-changed'; fi; " +
            "else printf '$TAG:%s\\n' \"\$c\"; fi;; *) printf '$TAG:%s\\n' \"\$r\";; esac"
        return "timeout ${registrationTimeoutSec + 30}s sh -c ${Shell.q(body)}"
    }

    fun verified(output: String): Boolean = output.lineSequence().lastOrNull { it.startsWith("$TAG:") } == "$TAG:ok"
    fun needsIdleInputProof(output: String): Boolean = output.lineSequence().lastOrNull { it.startsWith("$TAG:") } == "$TAG:idle-user"
    fun becameBusy(output: String): Boolean = output.lineSequence().lastOrNull { it.startsWith("$TAG:") } == "$TAG:became-busy"

    val chainScript = """
import hashlib,json,os,sys,time
${NativeControlMessages.pythonInterruptionFunction}
path,old,expected=sys.argv[1:4]
allow_user=sys.argv[4:]==['allow-idle-user']
session=os.path.splitext(os.path.basename(path))[0]
nodes={}; leaf=None; started=time.monotonic()
with open(path,'rb') as f:
 before=os.fstat(f.fileno())
 if before.st_size>1024*1024*1024: raise ValueError('History too large')
 while f.tell()<before.st_size:
  if time.monotonic()-started>20 or len(nodes)>500000: raise ValueError('History limit')
  raw=f.readline(min(32*1024*1024+1,before.st_size-f.tell()))
  if len(raw)>32*1024*1024: raise ValueError('Line too large')
  if not raw.endswith(b'\n'): break
  try: item=json.loads(raw)
  except (ValueError,UnicodeDecodeError): continue
  if not isinstance(item,dict) or item.get('isSidechain'): continue
  uid=item.get('uuid'); parent=item.get('parentUuid'); kind=item.get('type')
  if not isinstance(uid,str) or not (parent is None or isinstance(parent,str)): continue
  message=item.get('message'); content=message.get('content') if isinstance(message,dict) else None
  tool=isinstance(content,list) and any(isinstance(x,dict) and x.get('type')=='tool_result' for x in content)
  human=kind=='user' and not item.get('isMeta') and not tool
  text=content if isinstance(content,str) else None
  if isinstance(content,list) and len(content)==1 and isinstance(content[0],dict) and content[0].get('type')=='text': text=content[0].get('text')
  interrupted=yxi_native_interruption(item,session)
  if interrupted: human=False
  digest=hashlib.sha256(text.encode('utf-8')).hexdigest() if isinstance(text,str) else None
  fresh=uid not in nodes
  nodes[uid]=(parent,'interrupted' if interrupted else kind,human,digest)
  if fresh and kind in ('user','assistant'): leaf=uid
 after=os.fstat(f.fileno()); current=os.stat(path)
 identity=lambda s:(s.st_dev,s.st_ino,s.st_size,s.st_mtime_ns)
 if identity(before)!=identity(after) or identity(before)!=identity(current): raise ValueError('History changed')
if leaf is None or (nodes[leaf][1] not in ('assistant','interrupted') and not (allow_user and nodes[leaf][2])): raise ValueError('Reply not complete')
cursor=leaf; seen=set(); humans=[]
while cursor is not None:
 if cursor in seen or cursor not in nodes or cursor==old: raise ValueError('Wrong branch')
 seen.add(cursor)
 parent,kind,human,digest=nodes[cursor]
 if human: humans.append((parent,digest))
 cursor=parent
if humans!=[(None,expected)]: raise ValueError('Root message mismatch')
print('idle-user' if nodes[leaf][1]=='user' else 'ok')
""".trimIndent()
}
