package app.yxi.desktop

import app.yxi.agent.Session
import app.yxi.agent.NativeControlMessages
import app.yxi.agent.TranscriptStream
import app.yxi.ssh.Shell
import org.json.JSONObject

/** Read-only plan. The display item's key is never interpreted as a transcript UUID. */
internal data class RewindTarget(
    val file: String, val sessionId: String, val messageUuid: String, val parentUuid: String?,
    val laterUserMessages: Int, val size: Long, val modifiedNs: String, val unsupportedContent: Boolean = false,
)

internal object RewindTargets {
    /** Load content only for the exact inspected snapshot; never substitute a newer message. */
    suspend fun loadMessage(conn: Conn, target: RewindTarget): JSONObject {
        val raw = conn.ssh.exec("python3 -c ${Shell.q(messageScript)} ${Shell.q(target.file)} " +
            "${Shell.q(target.messageUuid)} ${target.size} ${Shell.q(target.modifiedNs)}")
        return try { JSONObject(raw) }
        catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            error("无法读取原消息或历史已变化，请重新打开编辑。")
        }
    }

    internal val messageScript = """
import json,os,sys
${NativeControlMessages.pythonInterruptionFunction}
path,target,wanted_size,wanted_time=sys.argv[1:]
session=os.path.splitext(os.path.basename(path))[0]
limit=32*1024*1024
message=None
with open(path,'rb') as f:
 before=os.fstat(f.fileno())
 if before.st_size!=int(wanted_size) or before.st_mtime_ns!=int(wanted_time): raise ValueError('History changed')
 if before.st_size>1024*1024*1024: raise ValueError('History too large')
 while f.tell()<before.st_size:
  raw=f.readline(min(limit+1,before.st_size-f.tell()))
  if len(raw)>limit: raise ValueError('Message too large')
  if not raw.endswith(b'\n'): break
  try: item=json.loads(raw)
  except (ValueError,UnicodeDecodeError): continue
  if not isinstance(item,dict) or item.get('uuid')!=target: continue
  if item.get('type')!='user' or item.get('isSidechain') or item.get('isMeta') or yxi_native_interruption(item,session): raise ValueError('Not an editable user message')
  message=item.get('message')
 after=os.fstat(f.fileno()); current=os.stat(path)
 identity=lambda s:(s.st_dev,s.st_ino,s.st_size,s.st_mtime_ns)
 if identity(before)!=identity(after) or identity(before)!=identity(current): raise ValueError('History changed')
if not isinstance(message,dict) or message.get('role')!='user': raise ValueError('Message missing')
encoded=json.dumps(message,ensure_ascii=False)
if len(encoded.encode('utf-8'))>limit: raise ValueError('Message too large')
print(encoded)
""".trimIndent()

    suspend fun inspect(conn: Conn, session: Session, sourceUuid: String): RewindTarget {
        require(Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}").matches(sourceUuid))
        val key = taskNavigationKey(conn.host, session)
        val file = DesktopTranscriptMemory.get(key)?.file ?: error("当前对话尚未载入，请稍后重试")
        check(TranscriptStream.latestFor(conn.ssh, session.cwd, session.name) == file) { "服务器已切换对话，请刷新后重新选择消息" }
        val script = inspectionScript
        val result = runCatching { JSONObject(conn.ssh.exec("python3 -c ${Shell.q(script)} ${Shell.q(file)} ${Shell.q(sourceUuid)}")) }
            .getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; error("无法确认此消息仍在当前历史分支，请刷新后重试") }
        return RewindTarget(file, result.getString("sessionId"), sourceUuid,
            result.optString("parent").takeIf { it.isNotBlank() && it != "null" }, result.getInt("later"), result.getLong("size"), result.getString("modifiedNs"), result.optBoolean("unsupportedContent"))
    }

    internal val inspectionScript = """
import json,os,sys
${NativeControlMessages.pythonInterruptionFunction}
path,target=sys.argv[1:]
session=os.path.splitext(os.path.basename(path))[0]
nodes={}; leaf=None; target_unsupported=False
injected=('teammate-message','agent-message','cross-session-message','task-notification','system-reminder','local-command-caveat','local-command-stdout','command-name')
with open(path,'rb') as f:
 st=os.fstat(f.fileno()); size=st.st_size
 while f.tell()<size:
  raw=f.readline(size-f.tell())
  if not raw.endswith(b'\n'): break
  try: item=json.loads(raw)
  except (ValueError,UnicodeDecodeError): continue
  if not isinstance(item,dict) or item.get('isSidechain'): continue
  uid=item.get('uuid'); parent=item.get('parentUuid')
  if not isinstance(uid,str) or not (parent is None or isinstance(parent,str)): continue
  kind=item.get('type')
  if kind not in ('user','assistant','system','progress','attachment'): continue
  message=item.get('message')
  content=message.get('content') if isinstance(message,dict) else None
  texts=[content] if isinstance(content,str) else []
  if isinstance(content,list):
   texts=[b.get('text') for b in content if isinstance(b,dict) and b.get('type')=='text' and isinstance(b.get('text'),str)]
   if any(isinstance(b,dict) and b.get('type')=='tool_result' for b in content): texts=[]
  text=any(t.strip() and not any('<'+tag in t for tag in injected) for t in texts)
  if uid==target:
   target_unsupported=(isinstance(content,list) and any(not isinstance(b,dict) or b.get('type')!='text' for b in content)) or sum(bool(t.strip()) and not any('<'+tag in t for tag in injected) for t in texts)>1
  human=kind=='user' and not item.get('isMeta',False) and text and not yxi_native_interruption(item,session)
  nodes[uid]=(parent,kind,human)
  if kind in ('user','assistant'): leaf=uid
  if len(nodes)>500000: raise ValueError('History too large')
 end=os.fstat(f.fileno())
 if end.st_size!=size or end.st_mtime_ns!=st.st_mtime_ns: raise ValueError('History changed')
if target not in nodes or not nodes[target][2]: raise ValueError('Not a user message')
seen=set(); later=0; cursor=leaf
while cursor is not None and cursor!=target:
 if cursor in seen or cursor not in nodes: raise ValueError('Message is not on current branch')
 seen.add(cursor)
 parent,kind,human=nodes[cursor]
 if human: later+=1
 cursor=parent
if cursor!=target: raise ValueError('Message is not on current branch')
parent=nodes[target][0]
if parent is not None and parent not in nodes: raise ValueError('Missing parent')
print(json.dumps({'sessionId':os.path.basename(path).removesuffix('.jsonl'),'parent':parent,'later':later,'size':size,'modifiedNs':str(st.st_mtime_ns),'unsupportedContent':target_unsupported}))
""".trimIndent()

}
