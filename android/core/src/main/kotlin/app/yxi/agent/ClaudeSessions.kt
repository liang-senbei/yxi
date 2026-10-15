package app.yxi.agent

import app.yxi.agent.Tr.t
import app.yxi.ssh.Shell
import app.yxi.ssh.SshSession
import org.json.JSONObject

/**
 * M4s：一台服务器上 **不是 Yxi 起的 Claude Code 会话** —— 终端里直接跑的 `claude`（entrypoint `cli`），
 * Claude App 的 Code 模式经 SSH 起的（SDK 驱动 claude CLI，entrypoint `sdk-cli`）。两者都在服务器上写同一套文件：
 *   · 历史：`~/.claude/projects/<cwd 编码>/<sessionId>.jsonl`（文件名就是 `claude --resume` 认的会话编号）；
 *   · 在跑的登记：`~/.claude/sessions/<pid>.json`（pid、sessionId、cwd、tmux、status、procStart…）。
 * claude.ai 网页 / 云端的 Code 会话不在服务器上，这里读不到、也不列。
 *
 * 读法同 [ConfigRemote] 的 GATHER、`BranchChanges.SCRIPT`：**一次 exec 跑一段 Python**，结果是标记 [MARKER] 后面的一行 JSON；
 * 整段套 `timeout`（主机没有这个命令就照常跑），服务器上不留任何状态。脚本只做这些：
 *   · 历史按修改时间取最新 [LIMIT] 个，每个只读开头一段和末尾一段（标题、目录、分支、最后活动、大约几条消息），不整份读；
 *     子 agent 的转录（`agent-*.jsonl`、整份都是 isSidechain、项目目录下面的子目录）跳过；
 *   · 登记只读 `*.json`。⚠️ 旁边的 `<pid>.<hash>.key` 是密钥：不读、不列进输出、不碰；登记里的 messagingSocketPath 也不用（不连别人的进程）；
 *   · 「活着」= pid 在、而且是同一个进程：procStart 对得上 /proc 里的启动时刻，对不上格式时退回「进程启动早于登记的 startedAt」
 *     （pid 被复用的话新进程一定起得更晚）；没有 /proc 的系统只看 pid 在不在（保守：宁可当活着、不给接着跑）。
 *     活着的进程顺手从 /proc/<pid>/cmdline 认出 `--resume <id>`（老版本 resume 会换新编号，靠它对回原会话）。
 *
 * 去重和标活在 Kotlin 里做（[visible]，纯函数、单测覆盖）：Yxi 侧栏已经列着的 tmux 会话里跑的那些不再列。
 * 手机端以后要用也是这一份：只依赖 SshSession，不碰界面。
 */
object ClaudeSessions {
    const val MARKER = "__YXI_CLAUDE_SESSIONS__:"
    /** 最多列这么多个历史（按修改时间取最新的）。 */
    const val LIMIT = 200
    /** 服务器上整段最多跑这么多秒：exec 占着这条连接的锁（审批按键、发送、抓屏都排在后面）。 */
    const val SECONDS = 20

    /**
     * 一份历史：[id] 是会话编号（= 文件名，`claude --resume` 用它），[path] 是服务器上的完整路径；[title] 依次取 /rename 起的名字、
     * 第一句真正由人打的话（斜杠命令、系统提醒、工具结果都不算）、summary、lastPrompt，压成一行最多 200 字；
     * [lastActivity] 毫秒（末尾那段里最大的 timestamp，没有就用修改时间）；[messages] 是 user / assistant 行数，[exact] 为假时按读到的比例估的。
     */
    data class Entry(
        val id: String, val path: String, val cwd: String, val title: String, val lastActivity: Long,
        val entrypoint: String = "", val branch: String = "", val messages: Int = 0, val exact: Boolean = true, val bytes: Long = 0,
    )

    /** 一条在跑登记（`~/.claude/sessions/<pid>.json`）：[live] 见类注释；[tmux] 是 tmux 会话名（登记里 `名字:@窗口.%窗格` 冒号前那段），不在 tmux 里为空；[resumed] 是命令行里 `--resume` 的编号。 */
    data class Process(
        val pid: Long, val sessionId: String, val live: Boolean, val resumed: String = "", val tmux: String = "",
        val cwd: String = "", val entrypoint: String = "", val status: String = "",
    )

    /** 一次读取的结果：[scanned] 是一共见到几个历史文件，[truncated] = 超过 [LIMIT] 只列了最新的；[millis] 是脚本在服务器上跑了多久。 */
    data class Snapshot(val history: List<Entry>, val registry: List<Process>, val scanned: Int = 0, val truncated: Boolean = false, val millis: Long = 0)

    /** 侧栏的一行：[process] 不为空 = 正在别处跑着（只读，不能在 Yxi 里接着跑）。 */
    data class Row(val entry: Entry, val process: Process?) {
        val live: Boolean get() = process != null
        val entrypoint: String get() = entry.entrypoint.ifBlank { process?.entrypoint.orEmpty() }
        /** 来源小字：终端 / Claude App / SDK / … */
        val origin: String get() = originOf(entrypoint)
        /** 圆点：在等人 = 等你，活着 = 干活中（实心），不在跑 = 空闲（空心环）。 */
        val state: SessionState get() = when {
            process == null -> SessionState.Idle
            process.status == "waiting" -> SessionState.NeedsYou
            else -> SessionState.Working
        }
    }

    /** entrypoint → 给人看的来源。`sdk-cli` 是 SDK 驱动的 claude CLI，Claude App 的 Code 模式就是这条路，但别的 SDK 程序也是，所以两个都写上。 */
    fun originOf(entrypoint: String): String = when {
        entrypoint == "cli" -> t("终端")
        entrypoint == "sdk-cli" -> "Claude App / SDK"
        entrypoint == "claude-vscode" -> "VS Code"
        entrypoint.startsWith("sdk") -> "SDK"
        entrypoint.isBlank() -> "Claude Code"
        else -> entrypoint.take(24)
    }

    /** 整条命令：没有 python3 时也打标记（带 error），好跟「连接断了 / 超时」分开说。 */
    fun command(limit: Int = LIMIT): String {
        val run = "python3 -c " + Shell.q(SCRIPT) + " " + limit.coerceIn(1, 500)
        return "command -v python3 >/dev/null 2>&1 || { printf '%s%s\\n' '$MARKER' '{\"error\":\"python3\"}'; exit 0; }; " +
            "if command -v timeout >/dev/null 2>&1; then timeout $SECONDS $run; else $run; fi"
    }

    /** 跑一次。读不成抛异常（消息是给人看的原因），调用方留着上一份。 */
    suspend fun read(ssh: SshSession, limit: Int = LIMIT): Snapshot = parse(ssh.exec(command(limit)))

    private val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    /** 项目目录名：Claude Code 把 cwd 里不是字母数字的字符都换成横杠（[Transcript.projectDirOf]），所以只放行这些字符（路径会拼进读转录的命令）。 */
    private val DIR = Regex("[A-Za-z0-9._-]{1,255}")
    private fun String.clean(max: Int) = filterNot { it.isISOControl() }.trim().take(max)

    /**
     * 脚本输出 → [Snapshot]。没有标记（连接中途断了、超时被 timeout 杀了）、JSON 坏了、主机没有 python3 都抛异常，不当成「没有会话」。
     * 逐条核：编号是 UUID；路径必须正好是 `<home>/.claude/projects/<一层目录>/<编号>.jsonl`、目录名只有字母数字和 `._-`（后面读转录直接用它，不许指到别处）；
     * 目录是绝对路径、不含控制字符（「在 Yxi 里继续」拿它当启动目录）。不合格的那条丢掉，其余照用。同一编号出现两次留最新的那份。
     */
    fun parse(raw: String): Snapshot {
        val line = raw.lineSequence().lastOrNull { it.startsWith(MARKER) } ?: error(t("没读到 Claude Code 会话（连接可能断了，或读取超时）"))
        val o = runCatching { JSONObject(line.removePrefix(MARKER)) }.getOrNull() ?: error(t("Claude Code 会话的读取结果不完整"))
        if (o.optString("error") == "python3") error(t("这台主机没有 python3，读不了 Claude Code 会话"))
        val home = o.optString("home").trimEnd('/')
        check(home.startsWith("/") && home.none { it.isISOControl() || it == '\'' }) { t("Claude Code 会话的读取结果不完整") }
        val root = "$home/.claude/projects/"
        val history = LinkedHashMap<String, Entry>()
        val items = o.optJSONArray("history")
        for (i in 0 until (items?.length() ?: 0)) {
            val h = items!!.optJSONObject(i) ?: continue
            val id = h.optString("id")
            val path = h.optString("path")
            val dir = path.removePrefix(root).removeSuffix("/$id.jsonl")
            val cwd = h.optString("cwd")
            if (!UUID.matches(id) || !path.startsWith(root) || path != "$root$dir/$id.jsonl" || !DIR.matches(dir) || dir == "." || dir == "..") continue
            if (!cwd.startsWith("/") || cwd.any { it < ' ' || it == '\u007f' }) continue
            if (id in history) continue
            history[id] = Entry(id, path, cwd, h.optString("title").clean(200), h.optLong("last").coerceAtLeast(0),
                h.optString("entrypoint").clean(40), h.optString("branch").clean(200), h.optInt("messages").coerceAtLeast(0),
                h.optBoolean("exact", true), h.optLong("bytes").coerceAtLeast(0))
        }
        val registry = ArrayList<Process>()
        val procs = o.optJSONArray("registry")
        for (i in 0 until (procs?.length() ?: 0)) {
            val p = procs!!.optJSONObject(i) ?: continue
            val pid = p.optLong("pid"); val id = p.optString("id")
            if (pid <= 0 || !UUID.matches(id)) continue
            registry += Process(pid, id, p.optBoolean("live"), p.optString("resumed").takeIf { UUID.matches(it) }.orEmpty(),
                p.optString("tmux").clean(64), p.optString("cwd").clean(4096), p.optString("entrypoint").clean(40), p.optString("status").clean(40))
        }
        return Snapshot(history.values.toList(), registry, o.optInt("scanned"), o.optBoolean("truncated"), o.optLong("ms"))
    }

    /**
     * 侧栏要列的行（新的在前）。**去掉 Yxi 已经列着的**：[tmuxSessions] 是这台主机此刻的 tmux 会话名（[SessionProbe] 那份，侧栏列的就是它们），
     * 登记里 tmux 名在其中的进程所跑的会话（它的编号和 `--resume` 的原编号）都算 Yxi 的 —— 不管登记还活不活（判活拿不准时宁可少列一条，
     * 不能让人对着一个其实在 tmux 里跑着的会话点「在 Yxi 里继续」）。[adopted] 是本机刚在 Yxi 里接着跑的（编号 → 新 tmux 名）：
     * 新进程的登记可能还没写出来，tmux 会话在就先按它去掉。
     * 剩下的里面，有活着的登记（编号或 `--resume` 对得上）的标成正在别处运行。
     */
    fun visible(snapshot: Snapshot, tmuxSessions: Collection<String>, adopted: Map<String, String> = emptyMap()): List<Row> {
        val names = tmuxSessions.toSet()
        val managed = HashSet<String>()
        val running = HashMap<String, Process>()
        for (p in snapshot.registry) {
            val ids = listOf(p.sessionId, p.resumed).filter { it.isNotEmpty() }
            if (p.tmux.isNotEmpty() && p.tmux in names) managed += ids
            else if (p.live) ids.forEach { running.putIfAbsent(it, p) }
        }
        adopted.forEach { (id, name) -> if (name in names) managed += id }
        return snapshot.history.filter { it.id !in managed }.map { Row(it, running[it.id]) }.sortedByDescending { it.entry.lastActivity }
    }

    /**
     * 服务器上跑的那段 Python（3.5 起都能跑：不用 f-string、海象、fromisoformat）。参数是最多列几个。输出只有一行：[MARKER] + JSON。
     * 历史文件只认 `<UUID>.jsonl`，不跟符号链接，最多看 5 万个；每个读开头 64 KiB（单行最多 256 KiB）和末尾 32 KiB。
     * 时间戳自己解析（带时区偏移的也换成 UTC 毫秒）。输出用 ensure_ascii：服务器的 LANG=C 时 stdout 是 ASCII，直接写中文会抛错。
     * ⚠️ 不加 internal：测试在 desktop 模块里拿它在本机对拍，internal 跨模块看不见（同 [SessionProbe.sent]）。
     */
    val SCRIPT = """
import calendar, json, os, re, sys, time
began = time.time()
limit = max(1, min(int(sys.argv[1]), 500)) if len(sys.argv) > 1 else 200
HEAD, TAIL, LINE, FILES = 65536, 32768, 262144, 50000
UUID = re.compile(r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}')
NOISE = ('<command-name', '<command-message', '<command-args', '<local-command-', '<system-reminder', '<bash-input', '<bash-stdout',
         '<bash-stderr', '<user-prompt-submit-hook', '<task-notification', '<teammate-message', '<agent-message', '<cross-session-message',
         'Caveat: The messages below', '[Request interrupted')
home = os.path.expanduser('~')
base = os.path.join(home, '.claude')
projects = os.path.join(base, 'projects')

def stamp(value):
    if not isinstance(value, str) or len(value) < 19: return 0
    try: sec = calendar.timegm(time.strptime(value[:19], '%Y-%m-%dT%H:%M:%S'))
    except ValueError: return 0
    rest = value[19:]
    ms = re.match(r'\.(\d{1,3})\d*', rest)
    if ms: rest = rest[ms.end():]
    zone = re.match(r'([+-])(\d\d):?(\d\d)', rest)
    if zone: sec -= (1 if zone.group(1) == '+' else -1) * (int(zone.group(2)) * 3600 + int(zone.group(3)) * 60)
    return sec * 1000 + (int(ms.group(1).ljust(3, '0')) if ms else 0)

def text_of(content):
    if isinstance(content, str): return content
    if not isinstance(content, list): return None
    parts = []
    for block in content:
        if not isinstance(block, dict): continue
        if block.get('type') == 'tool_result': return None
        if block.get('type') == 'text' and isinstance(block.get('text'), str): parts.append(block['text'])
    return '\n'.join(parts)

def prompt_of(row):
    if row.get('type') != 'user' or row.get('isMeta') or row.get('isSidechain') or row.get('isCompactSummary'): return None
    message = row.get('message')
    text = text_of(message.get('content')) if isinstance(message, dict) else None
    text = ' '.join(text.split()) if text else ''
    if not text or any(n in text for n in NOISE): return None
    return text

def parse(chunks):
    out = []
    for raw in chunks:
        try: row = json.loads(raw.decode('utf-8', 'replace'))
        except ValueError: continue
        if isinstance(row, dict): out.append(row)
    return out

def slices(path, size):
    with open(path, 'rb') as f:
        head, used, aligned = [], 0, True
        while used < HEAD and len(head) < 400:
            raw = f.readline(LINE)
            if not raw: break
            used += len(raw)
            aligned = raw.endswith(b'\n')
            if not aligned and len(raw) >= LINE: break
            head.append(raw)
        whole = used >= size
        tail = []
        if not whole:
            start = max(used, size - TAIL)
            f.seek(start)
            parts = f.read(size - start).split(b'\n')
            if start > used or not aligned: parts = parts[1:]
            tail = [p for p in parts if p.strip()]
            used += size - start
    return head, tail, whole, used

def history():
    found, scanned = [], 0
    try: dirs = list(os.scandir(projects))
    except OSError: return [], 0, False
    for d in dirs:
        if scanned >= FILES or not d.is_dir(follow_symlinks=False): continue
        try: files = list(os.scandir(d.path))
        except OSError: continue
        for f in files:
            name = f.name
            if scanned >= FILES or not name.endswith('.jsonl') or not UUID.fullmatch(name[:-6]): continue
            if not f.is_file(follow_symlinks=False): continue
            scanned += 1
            try: st = f.stat(follow_symlinks=False)
            except OSError: continue
            found.append((st.st_mtime, f.path, name[:-6], st.st_size))
    found.sort(reverse=True)
    out = []
    for mtime, path, sid, size in found[:limit]:
        try: head, tail, whole, read = slices(path, size)
        except OSError: continue
        first, last = parse(head), parse(tail)
        rows = first + last
        side = [r for r in rows if 'isSidechain' in r]
        if side and all(r.get('isSidechain') for r in side): continue
        cwd = next((r['cwd'] for r in rows if isinstance(r.get('cwd'), str) and r['cwd']), '')
        if not cwd: continue
        entry = next((r['entrypoint'] for r in rows if isinstance(r.get('entrypoint'), str) and r['entrypoint']), '')
        branch = next((r['gitBranch'] for r in reversed(rows) if isinstance(r.get('gitBranch'), str) and r['gitBranch']), '')
        custom = next((r['customTitle'] for r in reversed(rows) if r.get('type') == 'custom-title' and isinstance(r.get('customTitle'), str)), '')
        prompt = next((p for p in (prompt_of(r) for r in rows) if p), '')
        summary = next((r['summary'] for r in reversed(rows) if r.get('type') == 'summary' and isinstance(r.get('summary'), str)), '')
        recent = next((r['lastPrompt'] for r in reversed(rows) if isinstance(r.get('lastPrompt'), str) and r['lastPrompt'].strip()), '')
        title = ' '.join((custom or prompt or summary or recent).split())[:200]
        count = sum(1 for r in rows if r.get('type') in ('user', 'assistant') and not r.get('isSidechain') and not r.get('isMeta'))
        if count == 0 and not title: continue
        if not whole and read: count = int(round(count * size / float(read)))
        latest = max([stamp(r.get('timestamp')) for r in (last or first)] or [0]) or int(mtime * 1000)
        out.append({'id': sid, 'path': path, 'cwd': cwd, 'title': title, 'last': latest, 'entrypoint': entry, 'branch': branch,
                    'messages': count, 'exact': whole, 'bytes': size})
    return out, scanned, len(found) > limit

def boot():
    try:
        with open('/proc/stat') as f:
            for line in f:
                if line.startswith('btime '): return int(line.split()[1])
    except (OSError, ValueError): pass
    return None
BOOT = boot()
try: CLK = os.sysconf('SC_CLK_TCK')
except (AttributeError, ValueError, OSError): CLK = 100

def ticks(pid):
    try:
        with open('/proc/%d/stat' % pid, 'rb') as f: data = f.read()
    except OSError: return None
    fields = data[data.rfind(b')') + 2:].split()
    try: return int(fields[19])
    except (IndexError, ValueError): return None

def alive(pid, proc, started):
    t = ticks(pid)
    if t is None:
        if os.path.isdir('/proc/self'): return False
        try: os.kill(pid, 0)
        except PermissionError: return True
        except OSError: return False
        return True
    if proc is not None and not isinstance(proc, bool) and str(proc).strip() == str(t): return True
    if BOOT is None: return True
    begin = BOOT + t / float(CLK)
    if isinstance(proc, (int, float)) and not isinstance(proc, bool) and any(abs(proc / s - begin) <= 2 for s in (1.0, 1000.0)): return True
    if isinstance(started, (int, float)) and not isinstance(started, bool) and started > 0: return begin <= started / 1000.0 + 5
    return True

def resumed(pid):
    try:
        with open('/proc/%d/cmdline' % pid, 'rb') as f: args = f.read(65536).decode('utf-8', 'replace').split('\0')
    except OSError: return ''
    for i, a in enumerate(args):
        value = args[i + 1] if a in ('--resume', '-r', '--session-id') and i + 1 < len(args) else a.split('=', 1)[1] if a.startswith(('--resume=', '--session-id=')) else ''
        if UUID.fullmatch(value): return value
    return ''

def registry():
    folder = os.path.join(base, 'sessions')
    try: names = sorted(n for n in os.listdir(folder) if n.endswith('.json'))[:500]
    except OSError: return []
    out = []
    for n in names:
        p = os.path.join(folder, n)
        try:
            if os.path.islink(p) or not os.path.isfile(p) or os.path.getsize(p) > 65536: continue
            with open(p, 'rb') as f: o = json.loads(f.read().decode('utf-8', 'replace'))
        except (OSError, ValueError): continue
        if not isinstance(o, dict): continue
        pid, sid = o.get('pid'), o.get('sessionId')
        if not isinstance(pid, int) or isinstance(pid, bool) or pid <= 0 or not isinstance(sid, str) or not UUID.fullmatch(sid): continue
        live = alive(pid, o.get('procStart'), o.get('startedAt'))
        tmux = o.get('tmux') if isinstance(o.get('tmux'), str) else ''
        out.append({'pid': pid, 'id': sid, 'live': live, 'resumed': resumed(pid) if live else '', 'tmux': tmux.split(':')[0],
                    'cwd': o.get('cwd') if isinstance(o.get('cwd'), str) else '', 'entrypoint': o.get('entrypoint') if isinstance(o.get('entrypoint'), str) else '',
                    'status': o.get('status') if isinstance(o.get('status'), str) else ''})
    return out

items, scanned, truncated = history()
print('$MARKER' + json.dumps({'v': 1, 'home': home, 'history': items, 'registry': registry(), 'scanned': scanned,
                                               'truncated': truncated, 'ms': int((time.time() - began) * 1000)}))
""".trimIndent()
}
