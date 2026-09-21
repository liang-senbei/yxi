package app.yxi.desktop

import app.yxi.agent.TranscriptStream
import app.yxi.ssh.Shell
import app.yxi.ssh.SshSession
import org.json.JSONObject

/** Choose a cold-load byte range from the current parent chain, not abandoned tail rows. */
internal object TranscriptBranchStart {
    suspend fun inspect(ssh: SshSession, file: String, count: Int): Pair<Long, Long>? {
        val raw = ssh.exec("python3 -c ${Shell.q(script)} ${Shell.q(file)} ${count.coerceIn(1, 2000)}")
        val result = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (result.isNull("start")) return TranscriptStream.tailStart(ssh, file, count)
        val size = result.getLong("size")
        val start = result.getLong("start")
        return (size to start).takeIf { size >= 0 && start in 0..size }
    }

    internal val script = """
import json,os,sys
path,count=sys.argv[1],int(sys.argv[2])
nodes={}; leaf=None
with open(path,'rb') as f:
 size=os.fstat(f.fileno()).st_size
 while f.tell()<size:
  offset=f.tell(); raw=f.readline(size-offset)
  if not raw.endswith(b'\n'): break
  try: item=json.loads(raw)
  except (ValueError,UnicodeDecodeError): continue
  if not isinstance(item,dict) or item.get('isSidechain'): continue
  uid=item.get('uuid'); parent=item.get('parentUuid'); kind=item.get('type')
  if not isinstance(uid,str) or not uid or 'parentUuid' not in item: continue
  if not (parent is None or isinstance(parent,str)): continue
  if kind not in ('user','assistant','system','progress','attachment'): continue
  known=uid in nodes
  # Retain the first offset so repeated streaming updates remain in the range.
  first=nodes[uid][1] if known else offset
  nodes[uid]=(parent,first,kind)
  if not known and kind in ('user','assistant'): leaf=uid
  if len(nodes)>500000: raise ValueError('History index limit exceeded')
 if os.fstat(f.fileno()).st_size<size: raise ValueError('History truncated')
cursor=leaf; seen=set(); offsets=[]; messages=0
while cursor is not None and messages<count:
 if cursor in seen: raise ValueError('Cyclic history')
 seen.add(cursor)
 if cursor not in nodes: break
 parent,offset,kind=nodes[cursor]
 offsets.append(offset)
 if kind in ('user','assistant'): messages+=1
 cursor=parent
print(json.dumps({'size':size,'start':min(offsets) if offsets else None}))
""".trimIndent()
}
