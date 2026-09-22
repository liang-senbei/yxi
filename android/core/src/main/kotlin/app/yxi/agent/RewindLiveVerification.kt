package app.yxi.agent

/**
 * 精确回退后「新交互 Claude 已载入目标会话」的只读核实（设计见 windows-test-reports/rewind-relaunch-confirm-design.md）。
 *
 * ## 它解决什么
 *
 * [Rewind] 的 `relaunchCommand` 只能证明「复核过了、按键发出去了」（`ID_TAG:sent`）——
 * **sent ≠ 载入**。本核实插在投递之后、`RewindDeliveryGate.finishVerified` 之前：
 * 全部核对过了才许清票，任何一项不符都如实回代号，**不提供人工绕过**（清票归 root 的 UI 接线）。
 *
 * ## 核对流程（一条 exec；全部只读、零 token、零生产按键）
 *
 * 1. **注册轮询**（≤ [REG_TIMEOUT_SEC]，默认 20s，root 定）：`~/.claude/sessions/` 里 `tmux` 字段
 *    **精确等于** `#{session_name}:#{window_id}.#{pane_id}` 的最新登记（只比会话名前缀会把同名窗口
 *    里别的 pane 认成自己）。该登记必须：`sessionId` == 目标、`kind == "interactive"`、`pid` != 旧 pid
 *    （旧 pid 那份是 /exit 前的尸体，按 pid 跳过、不靠新旧排序撞运气）、pid 活着、
 *    `readlink /proc/<pid>/exe` == capture.exe、pid 沿 `/proc/<pid>/stat` 的 ppid 上溯属于**该 pane
 *    进程树**（`#{pane_pid}`，≤12 跳）、`status == "idle"` 且 `statusUpdatedAt`（**实测 epoch 毫秒**，
 *    /1000 与文件 mtime 差 0.01s，2026-09-21 本机 3 份样本核对）新于投递时刻。
 *    每一拍先核 tmux `runtimeId` + `paneId`（同名会话被重建顶包当场 `identity`）。
 * 2. **链核对**（注册就绪后**只扫一次**，不进轮询——大文件反复扫是自造 DoS）。对齐产品
 *    `Transcript` 的口径：**先取文件大小快照**，只认快照内的**完整换行行**（尾巴没写完的半行
 *    不是证据）；`isSidechain` 条目整个排除（侧链不是主链）；`uuid`→`parentUuid` 全建索引，
 *    **叶子 = 最新出现的 user/assistant 新 uuid**（重复 uuid 只更新内容、不把叶子抢回去）；
 *    从叶子完整回走：anchor 必须在链上、target 必须不在（append-only + resume 跟最新叶子 ⇒
 *    target 只该是旁支：在文件里合法，在活链上=没退成）。回走遇到**环**（`chain-cycle`）或
 *    **parent 指向不存在的节点**（`chain-parent-missing`）必须失败——宁可拒绝也不假 ok。
 *    上界：文件字节 [CHAIN_MAX_BYTES]、节点数 [CHAIN_NODE_CAP]、墙钟 [CHAIN_TIMEOUT_SEC]，
 *    超了 fail-closed。
 * 3. **尾部复核**（链 ok 之后、回 ok 之前**再核一遍**注册+身份）：链扫描可能耗时数秒，
 *    20s 轮询和扫描之间登记/进程可能变化——收口前重跑一次注册核对（单拍），过了才 `ok`。
 *    把「轮询就绪 → 扫描 → 清票」整段缝隙里的顶包/换会话窗口压到最窄。
 *
 * ## 不做什么（边界，如实）
 *
 * - **不读 cmdline**：cmdline 只证明旗标被传入；CC 自己写的登记才算「已接受」，单 cmdline 不作为载入证据。
 * - **不能证明 CC 内部 context 张量**恰等于该链——本核实证明的是 CC 自己的全部簿记都指向目标分支；
 *   残余风险是 CC 行为变化（可用登记 `version` 字段另行收窄，此处不断言）。
 * - 尾部复核之后、清票之前仍有人物理抢打键盘的竞态，已压到最窄但非零。
 * - 只回**代号 + pid**（非敏感身份）；值不回显。
 *
 * 产出命令给 `SshSession.exec`；也可直接 `bash -c` 跑（fixture 测试就是这么隔离执行的：
 * 真 bash + 真 python3 + 假 tmux + 真进程树 + 临时 HOME，不写真实登记、不付费）。
 */
object RewindLiveVerification {

    const val TAG = "__YXI_REWIND_LIVE__"

    /** 注册轮询上限（秒）。到点没等到合格登记就 [parse] 出 `no-reg`/`not-ready`，不无限等。 */
    const val REG_TIMEOUT_SEC = 20

    /** 轮询间隔（秒）。 */
    const val REG_INTERVAL_SEC = "0.5"

    /** 链扫描的文件字节上界（fail-closed：超过回 `chain-too-large`，绝不无界读）。 */
    const val CHAIN_MAX_BYTES: Long = 1L shl 30

    /** 链扫描墙钟上界（秒），超过回 `chain-timeout`。 */
    const val CHAIN_TIMEOUT_SEC = 30

    /** 链索引节点数上界，超过回 `chain-node-cap`（防病态文件把内存/时间打爆）。 */
    const val CHAIN_NODE_CAP = 500_000

    private val UUIDRX = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    /** 一次核实的全部输入；全部来自已核实的前序步骤（capture/snapshot/plan），不接受自由文本。 */
    data class Query(
        /** tmux 会话名（snapshot 现成的；`cc-<目录>` 形态）。 */
        val sessionName: String,
        /** 期望 `pid:session_id:session_created`（snapshot 的 [Session.runtimeId]，顶包即不符）。 */
        val runtimeId: String,
        /** 期望 pane id（`%N`，capture 那趟记下的）。 */
        val paneId: String,
        /** capture.exe（/proc 解析的真 binary；登记进程的 exe 必须与它一致）。 */
        val exe: String,
        /** 原 claude 进程 pid（capture 那趟的）；新 pid 必须与它不同。 */
        val oldPid: String,
        /** 目标会话 id（fork 时用 `Rewind.Outcome.Ok.sessionId` 的新 id）。 */
        val sessionId: String,
        /** 保留锚点（链上必须有）。 */
        val anchorUuid: String,
        /** 被回退的目标 user 消息 uuid（链上必须没有）。 */
        val targetUuid: String,
        /** 转录 jsonl 的**明确绝对路径**（调用方从 TranscriptStream 拿现成的，不在本类里猜）。 */
        val transcriptPath: String,
        /** 投递时刻（unix 秒）；`statusUpdatedAt` 必须新于它。 */
        val notBeforeEpochSec: Double,
    )

    /** @return null = 可执行；否则失败代号（命令生成前就地拒绝，不进 shell）。 */
    fun validate(q: Query): String? = when {
        !Regex("""^[A-Za-z0-9_.:@%+-]{1,128}$""").matches(q.sessionName) -> "session-name"
        !Regex("""^\d{1,10}:\$\d{1,10}:\d{1,12}$""").matches(q.runtimeId) -> "runtime-id"
        !Regex("""^%\d{1,10}$""").matches(q.paneId) -> "pane-id"
        !Regex("""^[A-Za-z0-9/._-]{2,300}$""").matches(q.exe) -> "exe-path"
        !Regex("""^\d{1,10}$""").matches(q.oldPid) -> "old-pid"
        !UUIDRX.matches(q.sessionId) -> "sid"
        !UUIDRX.matches(q.anchorUuid) -> "anchor"
        !UUIDRX.matches(q.targetUuid) -> "target"
        // 绝对路径：相对路径会落到服务器不可知的 cwd 上，核实个寂寞
        !Regex("""^/[A-Za-z0-9/._-]{1,1023}$""").matches(q.transcriptPath) -> "transcript-path"
        !q.notBeforeEpochSec.isFinite() || q.notBeforeEpochSec < 0 -> "not-before"
        else -> null
    }

    /**
     * 生成核实命令。轮询期间每一拍先核身份（顶包即 `identity` 终止）；注册就绪才扫一次链；
     * 链 ok 后尾部复核再跑一拍注册。超时/上界参数只给 fixture 测试收紧用，生产走默认（root 定 20s）。
     */
    fun command(
        q: Query,
        regTimeoutSec: Int = REG_TIMEOUT_SEC,
        chainMaxBytes: Long = CHAIN_MAX_BYTES,
        chainTimeoutSec: Int = CHAIN_TIMEOUT_SEC,
    ): String {
        require(validate(q) == null) { "query: ${validate(q)}" }
        require(regTimeoutSec in 1..600 && chainMaxBytes > 0 && chainTimeoutSec in 1..600)
        val iters = (regTimeoutSec * 2).coerceAtLeast(1)   // 0.5s 一拍
        // ⚠️ q() 只转义不包引号——python 脚本整体必须自己套单引号，漏了就是 root 审查指出的
        // 「生成的脚本根本不能执行」（多行无引号会被 shell 裂成碎片）。
        val reg = registrationCommand(RuntimeIdentity(q.sessionName, q.runtimeId, q.paneId, q.exe,
            q.oldPid, q.sessionId, q.notBeforeEpochSec))
        val chain = "python3 -c '${q(CHAIN_PY)}' " +
            "'${q(q.transcriptPath)}' ${chainMaxBytes} $chainTimeoutSec ${CHAIN_NODE_CAP} " +
            "'${q(q.anchorUuid)}' '${q(q.targetUuid)}'"
        // 轮询：硬代号（identity / sid-mismatch）与 READY 立即收；软代号继续等。
        // 链 ok 后尾部复核（REG 再跑一拍）：复核不过按原代号收，绝不带着过期证据回 ok。
        val body = "r=; for i in ${'$'}(seq 1 $iters); do " +
            "r=${'$'}($reg 2>/dev/null) || r=probe-fail; " +
            "case ${'$'}r in identity|sid-mismatch|READY*) break;; esac; " +
            "sleep $REG_INTERVAL_SEC; done; " +
            "case ${'$'}r in " +
            "READY*) c=${'$'}($chain 2>/dev/null) || c=chain-fail; " +
            "if [ \"${'$'}c\" = ok ]; then " +
            "r2=${'$'}($reg 2>/dev/null) || r2=probe-fail; " +
            "case ${'$'}r2 in " +
            "READY*) if [ \"${'$'}r2\" = \"${'$'}r\" ]; then echo \"$TAG:ok pid=${'$'}{r#READY }\"; " +
            "else echo \"$TAG:process-changed\"; fi;; " +
            "identity|sid-mismatch) echo \"$TAG:${'$'}r2\";; " +
            "*) echo \"$TAG:not-ready\";; esac; " +
            "else echo \"$TAG:${'$'}c\"; fi;; " +
            "not-ready:*) echo \"$TAG:not-ready\";; " +
            "none|stale-only|\"\") echo \"$TAG:no-reg\";; " +
            "*) echo \"$TAG:${'$'}r\";; esac"
        return "command -v timeout >/dev/null 2>&1 || { echo '$TAG:no-timeout'; exit 0; }; " +
            "timeout ${regTimeoutSec + chainTimeoutSec + 5}s sh -c '${q(body)}'; rlv_rc=\$?; " +
            "if [ \"\$rlv_rc\" = 124 ]; then echo '$TAG:timeout'; fi"
    }

    /** 认 [command] 的输出。@return 成功带新 pid（非敏感身份，UI 可展示）；失败带代号；没有锚行 = `noresult`。 */
    fun parse(out: String): Result {
        val line = out.lineSequence().map { it.trim() }
            .lastOrNull { it.startsWith("$TAG:") } ?: return Result.Failed("noresult")
        val body = line.removePrefix("$TAG:")
        if (body.startsWith("ok pid=")) {
            val pid = body.removePrefix("ok pid=")
            if (Regex("""^\d{1,10}$""").matches(pid)) return Result.Ok(pid)
        }
        return Result.Failed(body.ifBlank { "noresult" })
    }

    sealed interface Result {
        data class Ok(val pid: String) : Result
        /**
         * 代号：`identity`（runtimeId/pane 顶包）/ `sid-mismatch`（登记了别的会话）/
         * `no-reg`（到点无合格登记）/ `not-ready`（有登记但进程树/exe/idle/新鲜度没过）/
         * `probe-fail` / `chain-missing` `chain-too-large` `chain-timeout` `chain-node-cap`
         * `chain-no-leaf` `chain-cycle` `chain-parent-missing` `chain-anchor-missing`
         * `chain-target-present` / `chain-fail` / `noresult`。
         */
        data class Failed(val code: String) : Result
    }

    /** shell 单引号里安全地嵌一个值（`'\''` 四字符，同 [Rewind]）。⚠️ 只转义不包引号，调用处自己套。 */
    private fun q(v: String) = v.replace("'", "'\\''")

    data class RuntimeIdentity(val sessionName: String, val runtimeId: String, val paneId: String,
        val exe: String, val pid: String, val sessionId: String, val notBeforeEpochSec: Double)

    fun registrationCommand(identity: RuntimeIdentity, sameProcess: Boolean = false): String {
        require(Regex("^[A-Za-z0-9_.:@%+-]{1,128}$").matches(identity.sessionName))
        require(Regex("^\\d{1,10}:\\$\\d{1,10}:\\d{1,12}$").matches(identity.runtimeId))
        require(Regex("^%\\d{1,10}$").matches(identity.paneId))
        require(Regex("^[A-Za-z0-9/._-]{2,300}$").matches(identity.exe))
        require(Regex("^\\d{1,10}$").matches(identity.pid) && UUIDRX.matches(identity.sessionId))
        require(identity.notBeforeEpochSec.isFinite() && identity.notBeforeEpochSec >= 0)
        return "python3 -c '${q(REG_PY)}' '~/.claude/sessions' " +
            "'${q(identity.sessionName)}' '${q(identity.runtimeId)}' '${q(identity.paneId)}' " +
            "'${q(identity.exe)}' '${q(identity.pid)}' '${q(identity.sessionId)}' " +
            "'${identity.notBeforeEpochSec * 1000}' '${if (sameProcess) "same" else "new"}'"
    }

    // ⚠️ 两段 python 里不许出现单引号（要进 shell 单引号串）也不许 `$`（Kotlin 原样串直接嵌）；
    //    值一律走 argv。状态时间字段实测为 epoch 毫秒，与 SessionProbe 的 optDouble(...)/1000.0 同一口径。

    /** 注册核对一拍：身份 → 精确 pane 匹配最新登记 → sid/存活/exe/进程树/idle 新状态。轮询与尾部复核共用。 */
    private val REG_PY = """
import json, os, subprocess, sys

def tmux(fmt):
    p = subprocess.run(["tmux", "display-message", "-p", "-t", sys.argv[2], fmt],
                       capture_output=True, text=True)
    if p.returncode != 0:
        print("identity"); raise SystemExit
    return p.stdout.strip()

if tmux("#{pid}:#{session_id}:#{session_created}") != sys.argv[3]:
    print("identity"); raise SystemExit
if tmux("#{pane_id}") != sys.argv[4]:
    print("identity"); raise SystemExit
pane_pid = tmux("#{pane_pid}")
full = tmux("#{session_name}:#{window_id}.#{pane_id}")
d = os.path.expanduser(sys.argv[1])
cands = []
try:
    names = os.listdir(d)
except OSError:
    names = []
for fn in names:
    if not fn.endswith(".json"):
        continue
    p = os.path.join(d, fn)
    try:
        with open(p, encoding="utf-8") as f:
            o = json.load(f)
        m = os.path.getmtime(p)
    except Exception:
        continue
    if o.get("tmux") == full:
        cands.append((m, p, o))
cands.sort(reverse=True)
picked = None; saw_any = bool(cands)
for _, _, o in cands:
    pid = str(o.get("pid", ""))
    if not pid.isdigit():
        continue
    if (sys.argv[9] == "same") != (pid == sys.argv[6]):
        continue
    saw_any = False
    picked = (pid, o)
    break
if picked is None:
    print("stale-only" if saw_any else "none"); raise SystemExit
pid, o = picked
if str(o.get("sessionId")) != sys.argv[7]:
    print("sid-mismatch"); raise SystemExit
reason = None
try:
    os.kill(int(pid), 0)
except OSError:
    reason = "proc-dead"
if not reason:
    try:
        if os.path.realpath(os.readlink("/proc/%s/exe" % pid)) != os.path.realpath(sys.argv[5]):
            reason = "proc-exe"
    except OSError:
        reason = "proc-exe"
if not reason:
    ppid = pid; tree_ok = pid == pane_pid
    for _ in range(12):
        if tree_ok:
            break
        try:
            s = open("/proc/%s/stat" % ppid).read()
        except OSError:
            break
        s = s[s.rindex(")") + 2:].split()
        ppid = s[1]
        if ppid == pane_pid:
            tree_ok = True; break
    if not tree_ok:
        reason = "proc-tree"
if not reason:
    try:
        su = float(o.get("statusUpdatedAt", 0))
    except (TypeError, ValueError):
        su = 0.0
    if o.get("status") != "idle" or su < float(sys.argv[8]):
        reason = "status"
print("not-ready:%s" % reason if reason else "READY %s" % pid)
""".trimIndent()

    /**
     * 链核对（注册就绪后只跑一次）。对齐产品口径：大小快照内只认完整换行行；排除 isSidechain；
     * 叶子=最新 user/assistant 新 uuid（重复 uuid 只更新不夺叶）；回走遇环或缺 parent 必须失败。
     */
    private val CHAIN_PY = """
import json, os, sys, time
path = sys.argv[1]
try:
    f = open(path, "rb")
except OSError:
    print("chain-missing"); raise SystemExit
original = os.fstat(f.fileno()); size = original.st_size
if size > int(sys.argv[2]):
    print("chain-too-large"); raise SystemExit
t0 = time.monotonic(); deadline = float(sys.argv[3]); node_cap = int(sys.argv[4])
anchor = sys.argv[5]; target = sys.argv[6]
parent = {}; leaf = None; seen = set(); nodes = 0; pos = 0
try:
    while pos < size:
        if time.monotonic() - t0 > deadline:
            print("chain-timeout"); raise SystemExit
        if nodes > node_cap:
            print("chain-node-cap"); raise SystemExit
        line = f.readline(size - pos)
        if not line:
            break
        pos += len(line)
        if not line.endswith(b"\n"):
            break
        try:
            o = json.loads(line.decode("utf-8", "replace"))
        except Exception:
            continue
        if not isinstance(o, dict) or o.get("isSidechain") is True:
            continue
        u = o.get("uuid")
        if not isinstance(u, str) or not u:
            continue
        nodes += 1
        parent[u] = o.get("parentUuid")
        if u in seen:
            continue
        seen.add(u)
        if o.get("type") in ("user", "assistant"):
            leaf = u
    current = os.stat(path)
    if (current.st_dev, current.st_ino, current.st_size, current.st_mtime_ns) != (original.st_dev, original.st_ino, original.st_size, original.st_mtime_ns):
        print("chain-changed"); raise SystemExit
finally:
    f.close()
if leaf is None:
    print("chain-no-leaf"); raise SystemExit
anchor_ok = False; target_on = False; cur = leaf; seen = set()
while True:
    if cur == anchor:
        anchor_ok = True
    if cur == target:
        target_on = True
    if cur in seen:
        print("chain-cycle"); raise SystemExit
    seen.add(cur)
    if cur not in parent:
        print("chain-parent-missing"); raise SystemExit
    nxt = parent[cur]
    if nxt is None:
        break
    cur = nxt
if not anchor_ok:
    print("chain-anchor-missing")
elif target_on:
    print("chain-target-present")
else:
    print("ok")
""".trimIndent()
}
