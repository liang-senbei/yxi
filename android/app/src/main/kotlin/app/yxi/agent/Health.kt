package app.yxi.agent

/**
 * 服务器体检 —— 长按主机就能看见「这台机器现在喘不喘得过气」。
 *
 * 起因是 2026-08-29 那次：服务器卡到敲命令都要等几秒，而我第一轮诊断
 * **只看了 load 和内存，漏了 CPU steal**，于是得出一个自洽但不完整的结论。
 * 这个界面把当时该看的几个数一次摆齐，尤其是 [steal] —— 它是最容易漏的那个。
 *
 * ⚠️ **必须在「连不太上」的时候也能出数。** 所以：
 *  · 全部读 `/proc`，**一个进程都不 fork**（对比：查额度要跑 `claude -p '/usage'`，
 *    那是几秒到十几秒，机器一卡就更慢，正好在最需要它的时候用不了）。
 *  · 一个来回拿全，不分多次往返。
 *  · 唯一的耗时是算 steal 必须的那 1 秒采样间隔。
 */
object Health {

    /**
     * 体检的一份读数。
     *
     * @param steal 被宿主机抢走的 CPU 百分比。⚠️ **这个数在虚拟机内部无解** ——
     *   杀进程、加内存都降不下来，只能找服务商迁移实例。
     */
    data class Vitals(
        val cores: Int,
        val load1: Double,
        val memTotalKb: Long,
        val memAvailKb: Long,
        val swapTotalKb: Long,
        val swapFreeKb: Long,
        val steal: Int,
        val diskUsedPct: Int,
    ) {
        val loadPerCore: Double get() = if (cores > 0) load1 / cores else load1
        val memUsedPct: Int
            get() = if (memTotalKb <= 0) 0
            else (100 - memAvailKb * 100 / memTotalKb).toInt().coerceIn(0, 100)
        val swapUsedPct: Int
            get() = if (swapTotalKb <= 0) 0
            else ((swapTotalKb - swapFreeKb) * 100 / swapTotalKb).toInt().coerceIn(0, 100)
    }

    /**
     * 取一份读数的命令。
     *
     * ⚠️ **算 steal 必须采两次。** `/proc/stat` 给的是开机以来的累计值，
     * 单次读只能算出「15 天的平均」—— 那个数永远看着很正常，
     * 而我们要的是**此刻**。两次之间差 1 秒。
     * ⚠️ `sleep 1` 是唯一的耗时。别为了省这 1 秒去用累计值 ——
     * 那等于把这个功能做成一个永远说「一切正常」的摆设。
     */
    val COMMAND: String = """
        echo '#load'; cat /proc/loadavg 2>/dev/null
        echo '#cpu'; grep -c ^processor /proc/cpuinfo 2>/dev/null
        echo '#mem'; grep -E '^(MemTotal|MemAvailable|SwapTotal|SwapFree):' /proc/meminfo 2>/dev/null
        echo '#stat1'; head -1 /proc/stat 2>/dev/null
        sleep 1
        echo '#stat2'; head -1 /proc/stat 2>/dev/null
        echo '#disk'; df -P / 2>/dev/null | tail -1
    """.trimIndent()

    private fun section(out: String, tag: String): String {
        val a = out.indexOf("#$tag\n").takeIf { it >= 0 } ?: return ""
        val from = a + tag.length + 2
        val b = out.indexOf("\n#", from).takeIf { it >= 0 } ?: out.length
        return out.substring(from, b)
    }

    /**
     * 解析 [COMMAND] 的输出。
     *
     * ⚠️ **读不出来返回 null，绝不猜。** 体检报告里编一个数出来比不显示危险得多 ——
     * 用户会照着它做决定（比如「看着还好，那就不是服务器的问题」）。
     */
    fun parse(out: String): Vitals? {
        val load1 = section(out, "load").trim().split(' ').firstOrNull()?.toDoubleOrNull() ?: return null
        val cores = section(out, "cpu").trim().toIntOrNull()?.takeIf { it > 0 } ?: 1
        val mem = section(out, "mem").lineSequence().mapNotNull { line ->
            val p = line.split(':', limit = 2)
            if (p.size < 2) null else p[0].trim() to (p[1].trim().split(' ').firstOrNull()?.toLongOrNull() ?: 0L)
        }.toMap()
        val disk = section(out, "disk").trim().split(Regex("\\s+"))
            .firstOrNull { it.endsWith("%") }?.dropLast(1)?.toIntOrNull() ?: 0
        return Vitals(
            cores = cores,
            load1 = load1,
            memTotalKb = mem["MemTotal"] ?: 0,
            memAvailKb = mem["MemAvailable"] ?: 0,
            swapTotalKb = mem["SwapTotal"] ?: 0,
            swapFreeKb = mem["SwapFree"] ?: 0,
            steal = stealOf(section(out, "stat1").trim(), section(out, "stat2").trim()),
            diskUsedPct = disk,
        )
    }

    /**
     * 从两次 `/proc/stat` 的 cpu 行算出这一秒里 steal 占多少。
     *
     * `cpu  user nice system idle iowait irq softirq steal guest guest_nice`
     * —— steal 是第 8 个数字（下标 7）。
     */
    internal fun stealOf(a: String, b: String): Int {
        fun nums(s: String) = s.removePrefix("cpu").trim().split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }
        val x = nums(a)
        val y = nums(b)
        if (x.size < 8 || y.size < 8) return 0
        val total = (0 until minOf(x.size, y.size)).sumOf { y[it] - x[it] }
        if (total <= 0) return 0
        return ((y[7] - x[7]) * 100 / total).toInt().coerceIn(0, 100)
    }

    /**
     * 一条扣分理由。
     *
     * ⚠️ **只带代号和数字，不带话术。** [Health] 是纯逻辑（能单测、拿不到 Context），
     * 在这儿写中文的话英文界面会原样吐中文 —— 而且这些句子是**插值拼出来的**
     * （`"被抢 ${steal}%"`），连 `t()` 都匹配不上，`dev/i18n-check.sh` 更看不见。
     * 跟 [Dirs.Made] 是同一个教训。话术在 UI 层用 `t()` 拼。
     *
     * @param code steal / swap / load / mem / disk
     * @param level 0 = 最轻，数字越大越严重
     * @param value 这条对应的那个数（百分比，或负载倍数×10）
     * @param fixable 一键修复能不能帮上忙
     */
    data class Issue(val code: String, val level: Int, val value: Int, val cost: Int, val fixable: Boolean)

    /** 体检结论：分数 + 扣在哪儿。 */
    data class Report(val score: Int, val issues: List<Issue>, val vitals: Vitals)

    /**
     * 打分。**从 100 分往下扣**，每一条都说清为什么扣。
     *
     * ⚠️ **权重是按「什么最先让人用不了机器」定的，不是按数字好不好看**：
     *  · **steal 最重** —— 它最隐蔽（`uptime`/`free` 里根本没有），
     *    而且**在虚拟机内部无解**。扣得狠是为了把它顶到用户眼前。
     *  · **swap 次重** —— 一旦开始换页，每次内存访问都可能等磁盘，
     *    体感是「所有东西一起变慢」，比单纯 CPU 忙难受得多。
     *  · load 和内存再次之：它们高通常有明确的元凶，杀掉就好。
     *  · 磁盘满最后：它坏的方式是「写失败」，不是「变慢」。
     *
     * ⚠️ **每条都标了 fixable。** steal 和磁盘满**不 fixable** ——
     * 一键修复不能假装能解决它们，否则用户按了没效果只会更困惑。
     */
    fun score(v: Vitals): Report {
        val issues = mutableListOf<Issue>()

        // steal：>50% 是宿主机严重超卖，>10% 就该找服务商了
        when {
            v.steal >= 50 -> issues += Issue("steal", 2, v.steal, 40, false)
            v.steal >= 25 -> issues += Issue("steal", 1, v.steal, 25, false)
            v.steal >= 10 -> issues += Issue("steal", 0, v.steal, 12, false)
        }

        // swap：用起来就是在换页
        when {
            v.swapUsedPct >= 70 -> issues += Issue("swap", 2, v.swapUsedPct, 30, true)
            v.swapUsedPct >= 30 -> issues += Issue("swap", 1, v.swapUsedPct, 15, true)
            v.swapUsedPct >= 5 -> issues += Issue("swap", 0, v.swapUsedPct, 6, true)
        }

        // load：按每核算，超过 1 就是有人在排队
        val lpc = v.loadPerCore
        when {
            lpc >= 3 -> issues += Issue("load", 1, (lpc * 10).toInt(), 20, true)
            lpc >= 1.5 -> issues += Issue("load", 0, (lpc * 10).toInt(), 10, true)
        }

        when {
            v.memUsedPct >= 92 -> issues += Issue("mem", 1, v.memUsedPct, 15, true)
            v.memUsedPct >= 80 -> issues += Issue("mem", 0, v.memUsedPct, 6, true)
        }

        when {
            v.diskUsedPct >= 95 -> issues += Issue("disk", 1, v.diskUsedPct, 15, false)
            v.diskUsedPct >= 85 -> issues += Issue("disk", 0, v.diskUsedPct, 5, false)
        }

        return Report(
            score = (100 - issues.sumOf { it.cost }).coerceIn(0, 100),
            issues = issues.sortedByDescending { it.cost },
            vitals = v,
        )
    }

    /** 分数对应的代号（话术在 UI 层，理由同 [Issue]）。 */
    fun verdict(score: Int): String = when {
        score >= 85 -> "easy"
        score >= 65 -> "tight"
        score >= 40 -> "strained"
        else -> "drowning"
    }

    // ────────────── 一键修复 ──────────────

    /**
     * 一个可以安全收掉的东西。
     *
     * @param what 代号：gradle / kotlin / rg / hog / idle（话术在 UI 层）
     * @param session 只有 `idle` 有：要收掉的 tmux 会话名。**非空就走会话那条路，不按 pid 杀** ——
     *   见 [killCommand]。
     * @param ageSec 进程类是「跑了多久」，`idle` 是「多久没动过」。
     */
    data class Junk(
        val pid: Int,
        val rssKb: Long,
        val ageSec: Long,
        val what: String,
        val session: String = "",
    )

    /**
     * 找出**可以安全收掉**的东西。
     *
     * ⚠️ **白名单，不是黑名单。** 只认这几类，别的一概不碰：
     *  · **Gradle / Kotlin 编译守护进程** —— 它们是构建缓存，杀了下次编译慢一点，
     *    没有任何东西会丢。（我自己就在用户的机器上堆到过 3.1 GB。）
     *  · **跑了 10 分钟以上的 `rg`** —— VS Code 的全盘搜索跑飞了。正常搜索几秒就完，
     *    跑十分钟的一定是失控的；杀了编辑器会自己重开。
     *
     * ⚠️ **绝不碰** `claude`（用户的活）、`tmux`（杀了所有会话一起没）、
     * `sshd`（杀了你自己就断线了）、编辑器本体。
     * 一键修复要是能弄丢东西，它就不是「方便」，是陷阱。
     */
    /**
     * ⚠️⚠️ **「闲置」不能只看 `tmux session_activity`。**
     *
     * 那个值**在没人 attach 时不随输出更新** —— 一个正跑着的会话可以显示「3 天没动」。
     * 本文件隔壁的 [SessionProbe.lastActivityOf] 早就写过这条，我还是照着它清理过一次
     * 用户正在用的会话（`cc-hexingyang`：tmux 说闲了 3.9 天，转录 15 分钟前还在写）。
     * **让一个「一键收拾」按钮杀掉用户正在跑的活，是这个功能最坏的失败方式。**
     *
     * 现在按 `max(tmux 活动, 转录 mtime)` 判，转录按 **sessionId** 找 ——
     * 不按目录找，因为会话 `cd` 之后 cwd 会漂（见 [Transcript]）。
     *
     * ⚠️ **查不到就不列（fail-closed）。** 解析不出 sessionId、找不到对应转录、
     * 或者 Claude Code 自己报着 busy / waiting —— 一律跳过。
     * 解析出错的后果只能是「少列几个」，绝不能是「多杀一个」。
     */
    val SCAN_COMMAND: String = """
        { ps -eo pid=,ppid=,rss= 2>/dev/null
          echo '--yxiscan--'
          ps -eo pid=,rss=,etimes=,pcpu=,args= 2>/dev/null
          echo '--yxiscan--'
          find "${'$'}HOME/.claude/projects" -maxdepth 2 -name '*.jsonl' -printf '%f\t%T@\n' 2>/dev/null
          echo '--yxiscan--'
          awk 1 "${'$'}HOME"/.claude/sessions/*.json 2>/dev/null
          echo '--yxiscan--'
          tmux list-sessions -F '#{session_name}|#{session_activity}|#{session_attached}|#{pane_pid}' 2>/dev/null
        } | awk -v NOW="${'$'}(date +%s)" '
          /^--yxiscan--${'$'}/ { sec++; next }
          sec==0 { rss[${'$'}1]=${'$'}3; kid[${'$'}2] = kid[${'$'}2] " " ${'$'}1; next }
          sec==1 {
            pid=${'$'}1; r=${'$'}2; age=${'$'}3; cpu=${'$'}4+0
            line=""; for (i=5; i<=NF; i++) line = line ${'$'}i " "
            if (line ~ /yxiscan/) next
            what=""
            if (line ~ /GradleDaemon/)             what="gradle"
            else if (line ~ /KotlinCompileDaemon/) what="kotlin"
            else if (line ~ /(^|\/)rg( |${'$'})/ && age > 600) what="rg"
            else if (cpu >= 50 && age >= 1800 && line !~ /claude|tmux|sshd|systemd|\/init|Xtigervnc|Xvnc|vncserver|dockerd|containerd|[ \/]node |[ \/]java |nginx|postgres|mysqld|mongod|redis/) what="hog"
            if (what != "") printf "%s\t%s\t%s\t%s\t\n", pid, r, age, what
            next
          }
          sec==2 { sub(/\.jsonl${'$'}/, "", ${'$'}1); t=${'$'}2+0; if (t > trm[${'$'}1]) trm[${'$'}1]=t; next }
          sec==3 {
            if (!match(${'$'}0, /"tmux":"[^":]+/)) next
            tn = substr(${'$'}0, RSTART+8, RLENGTH-8)
            sid=""; if (match(${'$'}0, /"sessionId":"[^"]+"/)) sid = substr(${'$'}0, RSTART+13, RLENGTH-14)
            st="";  if (match(${'$'}0, /"status":"[^"]+"/))    st  = substr(${'$'}0, RSTART+10, RLENGTH-11)
            ssid[tn]=sid; sst[tn]=st
            next
          }
          sec==4 {
            n=split(${'$'}0, f, "|"); if (n != 4) next
            name=f[1]
            if (f[3]+0 != 0) next
            if (sst[name] == "busy" || sst[name] == "waiting") next
            sid = ssid[name]
            if (sid == "" || !(sid in trm)) next
            last = trm[sid]; if (f[2]+0 > last) last = f[2]+0
            idle = NOW - last; if (idle < 259200) next
            t=0; q[1]=f[4]+0; h=1; e=1
            while (h <= e) { p=q[h++]; t += rss[p]+0
              m=split(kid[p], c, " "); for (j=1; j<=m; j++) if (c[j] != "") q[++e]=c[j] }
            printf "0\t%s\t%s\tidle\t%s\n", t, idle, name
          }'
    """.trimIndent()

    /** 读 [SCAN_COMMAND] 的输出。认不出的行直接跳过 —— 宁可少杀不可错杀。 */
    fun junkFrom(out: String): List<Junk> =
        out.lineSequence().mapNotNull { line ->
            val p = line.split('\t')
            if (p.size < 4) return@mapNotNull null
            val pid = p[0].trim().toIntOrNull() ?: return@mapNotNull null
            val what = p[3].trim()
            val session = p.getOrNull(4)?.trim().orEmpty()
            // ⚠️ 两种行的必要字段不一样，缺了就整行丢掉：
            //   会话行没有名字 → 生成的命令会 `kill-session -t ''`，杀不掉也说不清；
            //   进程行没有真 pid → 更糟，[killCommand] 会把它算进 kill 列表。
            if (what.isEmpty()) return@mapNotNull null
            if (what == "idle" && session.isEmpty()) return@mapNotNull null
            if (what != "idle" && pid <= 1) return@mapNotNull null
            Junk(pid, p[1].trim().toLongOrNull() ?: 0, p[2].trim().toLongOrNull() ?: 0, what, session)
        }.toList()

    /**
     * 收掉这些。
     *
     * ⚠️ **两条路，别混。**
     *  · 进程类（gradle / kotlin / rg / hog）：**先 TERM 再 KILL**，中间等 2 秒。
     *    Gradle 收到 TERM 会把缓存写完再退，直接 -9 会留下坏掉的构建缓存。
     *  · 会话类（idle）：**按会话名收，不按 pid 杀**。一个会话底下是
     *    「shell → claude → 一堆子进程」，挨个 kill 会把 shell 杀在 claude 前头，
     *    留下一个挂在 init 底下、内存照占的孤儿。`tmux kill-session` 一次收干净。
     *
     * ⚠️ **有 `cloud-forget` 就先用它。** remote-dev-station 那套机器上有个
     * `cloud-watchdog` 定时器，**每 15 秒把「登记过但没在跑」的会话 `claude --resume` 拉回来** ——
     * 只 `tmux kill-session` 的话，十几秒后它原样复活，用户按了半天以为没生效。
     * `cloud-forget` 是先移出恢复名单再杀，而且**保留对话存档**（日后照样能 resume）。
     * 没装那套的机器上 `command -v` 直接落空，退回 `tmux kill-session`，行为不变。
     *
     * ⚠️ pid 只从 [junkFrom] 来 —— **不接受界面传任意数字**，
     * 免得哪天改 UI 时把一个能杀任何进程的口子留在那儿。
     */
    fun killCommand(junk: List<Junk>): String? {
        val pids = junk.filter { it.session.isEmpty() }.map { it.pid }.filter { it > 1 }.distinct()
        val sessions = junk.mapNotNull { it.session.takeIf(String::isNotBlank) }.distinct()
        if (pids.isEmpty() && sessions.isEmpty()) return null
        val parts = ArrayList<String>()
        if (pids.isNotEmpty()) {
            val list = pids.joinToString(" ")
            parts += "kill -TERM $list 2>/dev/null; sleep 2; kill -KILL $list 2>/dev/null"
        }
        sessions.forEach { name ->
            val q = "'" + name.replace("'", "'\\''") + "'"
            parts += "if command -v cloud-forget >/dev/null 2>&1; then cloud-forget $q >/dev/null 2>&1; " +
                "else tmux kill-session -t $q 2>/dev/null; fi"
        }
        return parts.joinToString("; ") + "; true"
    }
}
