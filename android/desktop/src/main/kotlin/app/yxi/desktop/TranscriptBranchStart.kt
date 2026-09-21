package app.yxi.desktop

import app.yxi.agent.TranscriptStream
import app.yxi.ssh.Shell
import app.yxi.ssh.SshSession
import org.json.JSONObject

/** Choose a cold-load byte range from the current parent chain, not abandoned tail rows. */
internal object TranscriptBranchStart {
    data class Snapshot(val size: Long, val start: Long, val lines: List<String>?)
    suspend fun load(ssh: SshSession, file: String, count: Int): Snapshot? {
        val raw = ssh.exec("python3 -c ${Shell.q(script)} ${Shell.q(file)} ${count.coerceIn(1, 2000)} snapshot")
        val result = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (result.isNull("start")) {
            val tail = TranscriptStream.tailStart(ssh, file, count) ?: return null
            return Snapshot(tail.first, tail.second, null)
        }
        val lines = result.getJSONArray("lines")
        return Snapshot(result.getLong("resume"), result.getLong("start"),
            (0 until lines.length()).map { lines.getString(it) })
    }

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
snapshot=len(sys.argv)>3 and sys.argv[3]=='snapshot'
nodes={}; leaf=None; complete=0; queued=[]; mode=None
with open(path,'rb') as f:
 original=os.fstat(f.fileno()); size=original.st_size
 while f.tell()<size:
  offset=f.tell(); raw=f.readline(size-offset)
  if not raw.endswith(b'\n'): break
  complete=f.tell()
  try: item=json.loads(raw)
  except (ValueError,UnicodeDecodeError): continue
  if not isinstance(item,dict) or item.get('isSidechain'): continue
  uid=item.get('uuid'); parent=item.get('parentUuid'); kind=item.get('type')
  if snapshot and kind=='queue-operation':
   op=item.get('operation'); content=item.get('content','')
   if op=='enqueue': queued.append((content,offset,len(raw)))
   elif op in ('remove','popAll','dequeue'):
    if op=='dequeue' or not content:
     if queued: queued.pop(0)
    else:
     for i,q in enumerate(queued):
      if q[0]==content: queued.pop(i); break
  if snapshot and kind=='mode': mode=(offset,len(raw))
  if not isinstance(uid,str) or not uid or 'parentUuid' not in item: continue
  if not (parent is None or isinstance(parent,str)): continue
  if kind not in ('user','assistant','system','progress','attachment'): continue
  known=uid in nodes
  # Retain the first offset so repeated streaming updates remain in the range.
  first=nodes[uid][1] if known else offset
  nodes[uid]=(parent,first,kind,offset,len(raw))
  if not known and kind in ('user','assistant'): leaf=uid
  if len(nodes)>500000: raise ValueError('History index limit exceeded')
 if os.fstat(f.fileno()).st_size<size: raise ValueError('History truncated')
cursor=leaf; seen=set(); offsets=[]; ordered=[]; messages=0
while cursor is not None and messages<count:
 if cursor in seen: raise ValueError('Cyclic history')
 seen.add(cursor)
 if cursor not in nodes: break
 ordered.append(cursor)
 parent,offset,kind,_,_=nodes[cursor]
 offsets.append(offset)
 if kind in ('user','assistant'): messages+=1
 cursor=parent
result={'size':size,'start':min(offsets) if offsets else None}
if snapshot:
 # A repeated ancestor can have its newest payload physically after the leaf.
 # Render parent order, not latest-write order, or it would look like a new root.
 ranges=[(nodes[uid][3],nodes[uid][4]) for uid in reversed(ordered)]
 ranges.extend((q[1],q[2]) for q in queued)
 if mode is not None: ranges.append(mode)
 lines=[]
 with open(path,'rb') as f:
  current=os.fstat(f.fileno())
  if (current.st_dev,current.st_ino)!=(original.st_dev,original.st_ino): raise ValueError('History replaced')
  if current.st_size<size: raise ValueError('History truncated')
  if current.st_size==size and current.st_mtime_ns!=original.st_mtime_ns: raise ValueError('History changed')
  for offset,length in ranges:
   f.seek(offset); raw=f.read(length)
   if len(raw)!=length or not raw.endswith(b'\n'): raise ValueError('History changed')
   lines.append(raw.decode('utf-8').rstrip('\n'))
 result.update({'resume':complete,'lines':lines})
print(json.dumps(result))
""".trimIndent()
}
