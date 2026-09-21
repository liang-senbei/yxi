package app.yxi.desktop

import app.yxi.agent.Session
import app.yxi.agent.TranscriptStream
import app.yxi.ssh.Shell
import org.json.JSONObject

/** Read-only plan. The display item's key is never interpreted as a transcript UUID. */
internal data class RewindTarget(
    val file: String, val sessionId: String, val messageUuid: String, val parentUuid: String?,
    val laterUserMessages: Int, val size: Long, val modifiedNs: String,
)

internal object RewindTargets {
    suspend fun inspect(conn: Conn, session: Session, sourceUuid: String): RewindTarget {
        require(Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}").matches(sourceUuid))
        val key = taskNavigationKey(conn.host, session)
        val file = DesktopTranscriptMemory.get(key)?.file ?: error("当前对话尚未载入，请稍后重试")
        check(TranscriptStream.latestFor(conn.ssh, session.cwd, session.name) == file) { "服务器已切换对话，请刷新后重新选择消息" }
        val script = """
import json,os,sys
path,target=sys.argv[1:]
nodes={}; leaf=None
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
  nodes[uid]=(parent,kind,bool(item.get('isMeta',False)))
  if kind in ('user','assistant'): leaf=uid
  if len(nodes)>500000: raise ValueError('History too large')
 end=os.fstat(f.fileno())
 if end.st_size!=size or end.st_mtime_ns!=st.st_mtime_ns: raise ValueError('History changed')
if target not in nodes or nodes[target][1]!='user' or nodes[target][2]: raise ValueError('Not a user message')
seen=set(); later=0; cursor=leaf
while cursor is not None and cursor!=target:
 if cursor in seen or cursor not in nodes: raise ValueError('Message is not on current branch')
 seen.add(cursor)
 parent,kind,meta=nodes[cursor]
 if kind=='user' and not meta: later+=1
 cursor=parent
if cursor!=target: raise ValueError('Message is not on current branch')
parent=nodes[target][0]
if parent is not None and parent not in nodes: raise ValueError('Missing parent')
print(json.dumps({'sessionId':os.path.basename(path).removesuffix('.jsonl'),'parent':parent,'later':later,'size':size,'modifiedNs':str(st.st_mtime_ns)}))
""".trimIndent()
        val result = runCatching { JSONObject(conn.ssh.exec("python3 -c ${Shell.q(script)} ${Shell.q(file)} ${Shell.q(sourceUuid)}")) }
            .getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; error("无法确认此消息仍在当前历史分支，请刷新后重试") }
        return RewindTarget(file, result.getString("sessionId"), sourceUuid,
            result.optString("parent").takeIf { it.isNotBlank() && it != "null" }, result.getInt("later"), result.getLong("size"), result.getString("modifiedNs"))
    }
}
