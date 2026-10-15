package app.yxi.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.yxi.ssh.Shell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * 分支改动条的数据（M4p）：[branch] 是分支名，分离 HEAD 时为空、看 [head]；[files] 是 status 里有改动的文件数（未跟踪的按单个文件数）；
 * [added] / [removed] 是工作区相对 HEAD 的增删行数（还没有提交时相对空仓库），未跟踪的文本文件按整份新增算。
 */
internal data class BranchStat(val branch: String, val head: String, val files: Int, val added: Int, val removed: Int) {
    val label: String get() = branch.ifBlank { if (head.isBlank()) "尚无提交" else "分离 HEAD · ${head.take(7)}" }
}

/**
 * 读分支改动条的数据（M4p）：本机直接起 git（[LocalGit.stat]），服务器经 SSH 跑一段只读的 Python（同「改动」面板的写法）；两边算法一样、结果同形。
 * 目录不属于 Git 仓库时是 null（不显示条）。[local]、[remote] 可替换：截图夹具不许起进程、不碰网络。
 */
internal object BranchChanges {
    /** 未跟踪文件最多数这么多个、每个最多读这么多字节（再多的不算行数，只算文件数）。 */
    const val UNTRACKED_FILES = 200
    const val UNTRACKED_BYTES = 1_048_576
    internal var local: suspend (directory: String) -> BranchStat? = { LocalGit.stat(it) }
    internal var remote: suspend (conn: Conn, directory: String) -> BranchStat? = ::remoteStat

    /** `git diff --numstat` 的增删合计；二进制文件那行是「-\t-\t路径」，不算。 */
    fun numstat(raw: String): Pair<Int, Int> {
        var added = 0; var removed = 0
        raw.lineSequence().forEach { line ->
            val parts = line.split('\t')
            if (parts.size >= 3) {
                val a = parts[0].toIntOrNull(); val r = parts[1].toIntOrNull()
                if (a != null && r != null) { added += a; removed += r }
            }
        }
        return added to removed
    }

    /** 一份未跟踪文件算几行新增：空的、含 NUL 的（当二进制）不算；最后一行没有换行也算一行（同 git diff 的计法）。 */
    fun lines(bytes: ByteArray): Int {
        if (bytes.isEmpty() || bytes.any { it == 0.toByte() }) return 0
        return bytes.count { it == '\n'.code.toByte() } + if (bytes.last() == '\n'.code.toByte()) 0 else 1
    }

    /** 脚本 / [LocalGit.stat] 的结果；不是仓库时脚本打 null。 */
    fun parse(raw: String): BranchStat? {
        if (raw.trim() == "null") return null
        val o = JSONObject(raw)
        return BranchStat(o.optString("branch"), o.optString("head"), o.optInt("files"), o.optInt("added"), o.optInt("removed"))
    }

    /** 服务器上的那份（算法同 [LocalGit.stat]）：只跑 git 的只读命令，未跟踪文件只读开头 1 MB、不跟符号链接。 */
    internal val SCRIPT = """
import json, os, subprocess, sys
cwd = sys.argv[1]
def git(*args):
    return subprocess.run(['git', '--no-pager', '-C', cwd, *args], capture_output=True, stdin=subprocess.DEVNULL, timeout=15)
top = git('rev-parse', '--show-toplevel')
if top.returncode:
    print('__YXI_BRANCH__:null'); sys.exit(0)
cwd = top.stdout.decode('utf-8', errors='replace').strip()
r = git('rev-parse', '--verify', '-q', 'HEAD'); head = r.stdout.decode().strip() if r.returncode == 0 else ''
r = git('symbolic-ref', '--short', '-q', 'HEAD'); branch = r.stdout.decode('utf-8', errors='replace').strip() if r.returncode == 0 else ''
status = git('status', '--porcelain=v1', '-z', '--untracked-files=all')
if status.returncode: raise SystemExit(status.stderr.decode(errors='replace')[:500])
records, files, index = status.stdout.split(b'\0'), [], 0
while index < len(records):
    record = records[index]; index += 1
    if len(record) < 4: continue
    code = record[:2].decode(); files.append((code, record[3:].decode('utf-8', errors='replace')))
    if 'R' in code or 'C' in code: index += 1
added = removed = 0
base = ['diff', '--numstat', '--no-ext-diff', '--no-textconv']
for args in ([base + [head]] if head else [base + ['--cached'], base]):
    for line in git(*args).stdout.decode('utf-8', errors='replace').splitlines():
        parts = line.split('\t')
        if len(parts) >= 3 and parts[0].isdigit() and parts[1].isdigit(): added += int(parts[0]); removed += int(parts[1])
for code, path in [f for f in files if f[0] == '??'][:${UNTRACKED_FILES}]:
    full = os.path.join(cwd, path)
    if os.path.isfile(full) and not os.path.islink(full):
        with open(full, 'rb') as handle: data = handle.read(${UNTRACKED_BYTES})
        if data and b'\0' not in data: added += data.count(b'\n') + (0 if data.endswith(b'\n') else 1)
print('__YXI_BRANCH__:' + json.dumps({'branch': branch, 'head': head, 'files': len(files), 'added': added, 'removed': removed}, ensure_ascii=False))
""".trimIndent()

    private suspend fun remoteStat(conn: Conn, directory: String): BranchStat? {
        if (!conn.ssh.isConnected) return null
        val raw = conn.ssh.exec("python3 -c " + Shell.q(SCRIPT) + " " + Shell.q(directory))
        val line = raw.lineSequence().lastOrNull { it.startsWith("__YXI_BRANCH__:") } ?: error("读不到分支改动")
        return parse(line.removePrefix("__YXI_BRANCH__:"))
    }
}

/**
 * 分支改动条（M4p，规格表 §3.3）：会话目录有未提交的改动时停在输入卡上方（下缘到输入卡 6.4）——#F0F0EF 底、撑满停靠区（768）、高 40、圆角 10；
 * 左边文件夹图标 + 分支名（弱色，起于左边 + 15）；右边「+N −N」徽章（点开「改动」面板，悬停写文件数）和关闭 ×（中心离右边 20）。
 * 参照端还有「创建 PR ∨」：Yxi 没有建 PR 的真实链路，不放（PRD §1）。只读：进页面、每轮结束各读一次，运行中每 15 秒再读；
 * [refresh] 变了也重读（服务器连上 / 断开）。关掉后改动数变了才再出现。读不成（不是仓库、没连上、没有 git / python3）就不显示，不报错——「改动」面板会说原因。
 */
@Composable
internal fun CodeBranchBar(key: Any, running: Boolean, load: suspend () -> BranchStat?, refresh: Any? = null, onOpen: () -> Unit) {
    val t = Tokens.current
    val p = CodePalette.current
    val read by rememberUpdatedState(load)
    var stat by remember(key) { mutableStateOf<BranchStat?>(null) }
    var dismissed by remember(key) { mutableStateOf<BranchStat?>(null) }
    LaunchedEffect(key, running, refresh) {
        while (true) {
            stat = try { read() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            if (!running) break
            delay(15_000)
        }
    }
    val s = stat?.takeIf { it.files > 0 && it != dismissed } ?: return
    Row(Modifier.padding(bottom = 6.4.dp).fillMaxWidth().height(40.dp).background(p.sunken, RoundedCornerShape(10.dp)).padding(start = 15.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.FolderOpen, null, Modifier.size(16.dp), tint = t.textMuted)
        Spacer(Modifier.width(6.4.dp))
        Text(s.label, Modifier.weight(1f), color = t.textMuted, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(8.dp))
        CodeHint("${s.files} 个文件有改动 · 查看改动") {
            val shape = RoundedCornerShape(6.dp)
            Row(Modifier.height(24.dp).clip(shape).background(p.sunken).border(hairline(), p.popupRing, shape)
                .clickable(role = Role.Button, onClick = onOpen).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("+${s.added}", color = t.diffAdd, fontSize = 12.sp, lineHeight = 16.sp)
                Spacer(Modifier.width(4.dp))
                Text("−${s.removed}", color = t.diffDel, fontSize = 12.sp, lineHeight = 16.sp)
            }
        }
        Spacer(Modifier.width(8.dp))
        CodeIconButton(Icons.Filled.Close, "关闭", iconSize = 14.dp, tint = t.textMuted, tipAbove = true) { dismissed = s }
    }
}
