import Foundation

/// 服务器体检 —— 长按主机就能看见「这台机器现在喘不喘得过气」。
///
/// 起因是 2026-08-29 那次：服务器卡到敲命令都要等几秒，而第一轮诊断
/// **只看了 load 和内存，漏了 CPU steal**，于是得出一个自洽但不完整的结论。
/// 这个界面把当时该看的几个数一次摆齐，尤其是 `steal` —— 它是最容易漏的那个。
///
/// ⚠️ **必须在「连不太上」的时候也能出数。** 所以：
///  · 全部读 `/proc`，**一个进程都不 fork**（对比：查额度要跑 `claude -p '/usage'`，
///    那是几秒到十几秒，机器一卡就更慢，正好在最需要它的时候用不了）。
///  · 一个来回拿全，不分多次往返。
///  · 唯一的耗时是算 steal 必须的那 1 秒采样间隔。
///
/// 跟安卓端 `app.yxi.agent.Health` 是同一份实现，测试用的也是**同一份真机输出**。
public enum Health {

    /// 体检的一份读数。
    public struct Vitals: Equatable, Sendable {
        public let cores: Int
        public let load1: Double
        public let memTotalKb: Int64
        public let memAvailKb: Int64
        public let swapTotalKb: Int64
        public let swapFreeKb: Int64
        /// 被宿主机抢走的 CPU 百分比。
        /// ⚠️ **这个数在虚拟机内部无解** —— 杀进程、加内存都降不下来，
        /// 只能找服务商迁移实例。
        public let steal: Int
        public let diskUsedPct: Int

        public var loadPerCore: Double { cores > 0 ? load1 / Double(cores) : load1 }
        public var memUsedPct: Int {
            guard memTotalKb > 0 else { return 0 }
            return min(100, max(0, Int(100 - memAvailKb * 100 / memTotalKb)))
        }
        public var swapUsedPct: Int {
            guard swapTotalKb > 0 else { return 0 }
            return min(100, max(0, Int((swapTotalKb - swapFreeKb) * 100 / swapTotalKb)))
        }
    }

    /// 取一份读数的命令。
    ///
    /// ⚠️ **算 steal 必须采两次。** `/proc/stat` 给的是开机以来的累计值，
    /// 单次读只能算出「开机以来的平均」—— 那个数永远看着很正常，
    /// 而我们要的是**此刻**。两次之间差 1 秒。
    /// ⚠️ `sleep 1` 是唯一的耗时。别为了省这 1 秒去用累计值 ——
    /// 那等于把这个功能做成一个永远说「一切正常」的摆设。
    public static let command = """
        echo '#load'; cat /proc/loadavg 2>/dev/null
        echo '#cpu'; grep -c ^processor /proc/cpuinfo 2>/dev/null
        echo '#mem'; grep -E '^(MemTotal|MemAvailable|SwapTotal|SwapFree):' /proc/meminfo 2>/dev/null
        echo '#stat1'; head -1 /proc/stat 2>/dev/null
        sleep 1
        echo '#stat2'; head -1 /proc/stat 2>/dev/null
        echo '#disk'; df -P / 2>/dev/null | tail -1
        """

    private static func section(_ out: String, _ tag: String) -> String {
        guard let a = out.range(of: "#\(tag)\n") else { return "" }
        let rest = out[a.upperBound...]
        if let b = rest.range(of: "\n#") { return String(rest[..<b.lowerBound]) }
        return String(rest)
    }

    /// 解析 `command` 的输出。
    ///
    /// ⚠️ **读不出来返回 nil，绝不猜。** 体检报告里编一个数出来比不显示危险得多 ——
    /// 用户会照着它做决定（比如「看着还好，那就不是服务器的问题」）。
    public static func parse(_ out: String) -> Vitals? {
        let loadTxt = section(out, "load").trimmingCharacters(in: .whitespacesAndNewlines)
        guard let load1 = Double(loadTxt.split(separator: " ").first.map(String.init) ?? "") else { return nil }
        let cores = Int(section(out, "cpu").trimmingCharacters(in: .whitespacesAndNewlines)).flatMap { $0 > 0 ? $0 : nil } ?? 1
        var mem: [String: Int64] = [:]
        for line in section(out, "mem").split(separator: "\n") {
            let p = line.split(separator: ":", maxSplits: 1)
            guard p.count == 2 else { continue }
            let v = p[1].trimmingCharacters(in: .whitespaces).split(separator: " ").first.map(String.init) ?? ""
            mem[String(p[0]).trimmingCharacters(in: .whitespaces)] = Int64(v) ?? 0
        }
        let disk = section(out, "disk").split(whereSeparator: { $0 == " " || $0 == "\t" || $0 == "\n" })
            .first(where: { $0.hasSuffix("%") })
            .flatMap { Int($0.dropLast()) } ?? 0
        return Vitals(
            cores: cores, load1: load1,
            memTotalKb: mem["MemTotal"] ?? 0, memAvailKb: mem["MemAvailable"] ?? 0,
            swapTotalKb: mem["SwapTotal"] ?? 0, swapFreeKb: mem["SwapFree"] ?? 0,
            steal: steal(section(out, "stat1"), section(out, "stat2")),
            diskUsedPct: disk
        )
    }

    /// 从两次 `/proc/stat` 的 cpu 行算出这一秒里 steal 占多少。
    ///
    /// `cpu  user nice system idle iowait irq softirq steal guest guest_nice`
    /// —— steal 是第 8 个数字（下标 7）。
    public static func steal(_ a: String, _ b: String) -> Int {
        func nums(_ s: String) -> [Int64] {
            s.replacingOccurrences(of: "cpu", with: "")
                .split(whereSeparator: { $0 == " " || $0 == "\n" || $0 == "\t" })
                .compactMap { Int64($0) }
        }
        let x = nums(a), y = nums(b)
        guard x.count >= 8, y.count >= 8 else { return 0 }
        let n = min(x.count, y.count)
        let total = (0..<n).reduce(Int64(0)) { $0 + (y[$1] - x[$1]) }
        guard total > 0 else { return 0 }
        return min(100, max(0, Int((y[7] - x[7]) * 100 / total)))
    }

    /// 一条扣分理由。
    ///
    /// ⚠️ **只带代号和数字，不带话术。** `Health` 是纯逻辑（能单测、拿不到界面），
    /// 而这些句子是插值拼出来的，写在这儿的话英文界面会原样吐中文。话术在 UI 层拼。
    public struct Issue: Equatable, Sendable {
        /// steal / swap / load / mem / disk
        public let code: String
        /// 0 = 最轻，数字越大越严重
        public let level: Int
        /// 这条对应的那个数（百分比，或负载倍数 ×10）
        public let value: Int
        public let cost: Int
        /// 一键收拾能不能帮上忙
        public let fixable: Bool
    }

    public struct Report: Equatable, Sendable {
        public let score: Int
        public let issues: [Issue]
        public let vitals: Vitals
    }

    /// 打分。**从 100 分往下扣**，每一条都说清为什么扣。
    ///
    /// ⚠️ **权重是按「什么最先让人用不了机器」定的，不是按数字好不好看**：
    ///  · **steal 最重** —— 它最隐蔽（`uptime`/`free` 里根本没有），
    ///    而且**在虚拟机内部无解**。扣得狠是为了把它顶到用户眼前。
    ///  · **swap 次重** —— 一旦开始换页，每次内存访问都可能等磁盘，
    ///    体感是「所有东西一起变慢」，比单纯 CPU 忙难受得多。
    ///  · load 和内存再次之：它们高通常有明确的元凶，杀掉就好。
    ///  · 磁盘满最后：它坏的方式是「写失败」，不是「变慢」。
    ///
    /// ⚠️ **每条都标了 fixable。** steal 和磁盘满**不 fixable** ——
    /// 一键收拾不能假装能解决它们，否则用户按了没效果只会更困惑。
    public static func score(_ v: Vitals) -> Report {
        var issues: [Issue] = []
        if v.steal >= 50 { issues.append(.init(code: "steal", level: 2, value: v.steal, cost: 40, fixable: false)) }
        else if v.steal >= 25 { issues.append(.init(code: "steal", level: 1, value: v.steal, cost: 25, fixable: false)) }
        else if v.steal >= 10 { issues.append(.init(code: "steal", level: 0, value: v.steal, cost: 12, fixable: false)) }

        if v.swapUsedPct >= 70 { issues.append(.init(code: "swap", level: 2, value: v.swapUsedPct, cost: 30, fixable: true)) }
        else if v.swapUsedPct >= 30 { issues.append(.init(code: "swap", level: 1, value: v.swapUsedPct, cost: 15, fixable: true)) }
        else if v.swapUsedPct >= 5 { issues.append(.init(code: "swap", level: 0, value: v.swapUsedPct, cost: 6, fixable: true)) }

        let lpc = v.loadPerCore
        if lpc >= 3 { issues.append(.init(code: "load", level: 1, value: Int(lpc * 10), cost: 20, fixable: true)) }
        else if lpc >= 1.5 { issues.append(.init(code: "load", level: 0, value: Int(lpc * 10), cost: 10, fixable: true)) }

        if v.memUsedPct >= 92 { issues.append(.init(code: "mem", level: 1, value: v.memUsedPct, cost: 15, fixable: true)) }
        else if v.memUsedPct >= 80 { issues.append(.init(code: "mem", level: 0, value: v.memUsedPct, cost: 6, fixable: true)) }

        if v.diskUsedPct >= 95 { issues.append(.init(code: "disk", level: 1, value: v.diskUsedPct, cost: 15, fixable: false)) }
        else if v.diskUsedPct >= 85 { issues.append(.init(code: "disk", level: 0, value: v.diskUsedPct, cost: 5, fixable: false)) }

        let total = issues.reduce(0) { $0 + $1.cost }
        return Report(score: min(100, max(0, 100 - total)),
                      issues: issues.sorted { $0.cost > $1.cost },
                      vitals: v)
    }

    /// 分数对应的代号（话术在 UI 层，理由同 `Issue`）。
    public static func verdict(_ score: Int) -> String {
        if score >= 85 { return "easy" }
        if score >= 65 { return "tight" }
        if score >= 40 { return "strained" }
        return "drowning"
    }

    // ────────────── 一键收拾 ──────────────

    public struct Junk: Equatable, Sendable {
        public let pid: Int
        public let rssKb: Int64
        /// 进程类是「跑了多久」，`idle` 是「多久没动过」。
        public let ageSec: Int64
        /// 代号：gradle / kotlin / rg / hog / idle（话术在 UI 层）
        public let what: String
        /// 只有 `idle` 有：要收掉的 tmux 会话名。**非空就走会话那条路，不按 pid 杀**。
        public let session: String

        public init(pid: Int, rssKb: Int64, ageSec: Int64, what: String, session: String = "") {
            self.pid = pid; self.rssKb = rssKb; self.ageSec = ageSec
            self.what = what; self.session = session
        }
    }

    /// 找出**可以安全收掉**的东西。
    ///
    /// ⚠️ **白名单，不是黑名单。** 只认这几类，别的一概不碰：
    ///  · **Gradle / Kotlin 编译守护进程** —— 构建缓存，杀了下次编译慢一点，什么都不会丢。
    ///  · **跑了 10 分钟以上的 `rg`** —— 编辑器的全盘搜索跑飞了。正常搜索几秒就完。
    ///  · **一直霸着 CPU 的进程** —— `pcpu` 是**生涯平均**，不是瞬时值：
    ///    一个进程能把半小时以上的平均压在 50% 以上，那就不是一阵子忙，是跑飞了。
    ///    （真事：一张挂了 4 小时的登录页把一整核吃满。）基础设施一概排除在外。
    ///  · **闲置 ≥3 天、没人连着的 tmux 会话** —— 这类才是内存大头：
    ///    实测一台机器上 30 个会话占了 11 GB（常驻 6.1 + 交换 5.0），
    ///    而白名单里那三类加起来只有几 MB。
    ///
    /// ⚠️ **必须把自己排除掉。** awk 的程序正文里写着 `/GradleDaemon/`，
    /// 而 `ps -eo args=` **会把这条 awk 自己列出来** —— 它于是把自己认成 Gradle 守护进程。
    /// 用户手机上那句「可以收拾 1 类 · 约 7 MB」收的就是它自己那几个临时 shell，
    /// 一键修复从上线起就是空的。`yxiscan` 这个记号只可能出现在扫描命令自身里，拿它自排除。
    ///
    /// ⚠️ **绝不碰** `claude`（用户的活）、`tmux`、`sshd`（杀了自己就断线）、编辑器本体。
    /// 一键修复要是能弄丢东西，它就不是「方便」，是陷阱。
    /// ⚠️⚠️ **「闲置」不能只看 `tmux session_activity`。**
    ///
    /// 那个值**在没人 attach 时不随输出更新** —— 一个正跑着的会话可以显示「3 天没动」。
    /// `SessionProbe.lastActivityOf` 早就写过这条，我还是照着它清理过一次用户正在用的会话
    /// （`cc-hexingyang`：tmux 说闲了 3.9 天，转录 15 分钟前还在写）。
    /// **让一个「一键收拾」按钮杀掉用户正在跑的活，是这个功能最坏的失败方式。**
    ///
    /// 现在按 `max(tmux 活动, 转录 mtime)` 判，转录按 **sessionId** 找 ——
    /// 不按目录找，因为会话 `cd` 之后 cwd 会漂。
    ///
    /// ⚠️ **查不到就不列（fail-closed）。** 解析不出 sessionId、找不到对应转录、
    /// 或者 Claude Code 自己报着 busy / waiting —— 一律跳过。
    /// 解析出错的后果只能是「少列几个」，绝不能是「多杀一个」。
    public static let scanCommand = """
        { ps -eo pid=,ppid=,rss= 2>/dev/null
          echo '--yxiscan--'
          ps -eo pid=,rss=,etimes=,pcpu=,args= 2>/dev/null
          echo '--yxiscan--'
          find "$HOME/.claude/projects" -maxdepth 2 -name '*.jsonl' -printf '%f\\t%T@\\n' 2>/dev/null
          echo '--yxiscan--'
          awk 1 "$HOME"/.claude/sessions/*.json 2>/dev/null
          echo '--yxiscan--'
          tmux list-sessions -F '#{session_name}|#{session_activity}|#{session_attached}|#{pane_pid}' 2>/dev/null
        } | awk -v NOW="$(date +%s)" '
          /^--yxiscan--$/ { sec++; next }
          sec==0 { rss[$1]=$3; kid[$2] = kid[$2] " " $1; next }
          sec==1 {
            pid=$1; r=$2; age=$3; cpu=$4+0
            line=""; for (i=5; i<=NF; i++) line = line $i " "
            if (line ~ /yxiscan/) next
            what=""
            if (line ~ /GradleDaemon/)             what="gradle"
            else if (line ~ /KotlinCompileDaemon/) what="kotlin"
            else if (line ~ /(^|\\/)rg( |$)/ && age > 600) what="rg"
            else if (cpu >= 50 && age >= 1800 && line !~ /claude|tmux|sshd|systemd|\\/init|Xtigervnc|Xvnc|vncserver|dockerd|containerd|[ \\/]node |[ \\/]java |nginx|postgres|mysqld|mongod|redis/) what="hog"
            if (what != "") printf "%s\\t%s\\t%s\\t%s\\t\\n", pid, r, age, what
            next
          }
          sec==2 { sub(/\\.jsonl$/, "", $1); t=$2+0; if (t > trm[$1]) trm[$1]=t; next }
          sec==3 {
            if (!match($0, /"tmux":"[^":]+/)) next
            tn = substr($0, RSTART+8, RLENGTH-8)
            sid=""; if (match($0, /"sessionId":"[^"]+"/)) sid = substr($0, RSTART+13, RLENGTH-14)
            st="";  if (match($0, /"status":"[^"]+"/))    st  = substr($0, RSTART+10, RLENGTH-11)
            ssid[tn]=sid; sst[tn]=st
            next
          }
          sec==4 {
            n=split($0, f, "|"); if (n != 4) next
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
            printf "0\\t%s\\t%s\\tidle\\t%s\\n", t, idle, name
          }'
        """

    /// 读 `scanCommand` 的输出。认不出的行直接跳过 —— **宁可少杀不可错杀**。
    public static func junk(from out: String) -> [Junk] {
        out.split(separator: "\n").compactMap { line in
            let p = line.split(separator: "\t", omittingEmptySubsequences: false)
            guard p.count >= 4, let pid = Int(p[0].trimmingCharacters(in: .whitespaces)) else { return nil }
            let what = p[3].trimmingCharacters(in: .whitespaces)
            let session = p.count > 4 ? p[4].trimmingCharacters(in: .whitespaces) : ""
            // ⚠️ 两种行的必要字段不一样，缺了就整行丢掉：
            //   会话行没有名字 → 生成的命令会 `kill-session -t ''`，杀不掉也说不清；
            //   进程行没有真 pid → 更糟，killCommand 会把它算进 kill 列表。
            if what.isEmpty { return nil }
            if what == "idle" && session.isEmpty { return nil }
            if what != "idle" && pid <= 1 { return nil }
            return Junk(pid: pid,
                        rssKb: Int64(p[1].trimmingCharacters(in: .whitespaces)) ?? 0,
                        ageSec: Int64(p[2].trimmingCharacters(in: .whitespaces)) ?? 0,
                        what: what,
                        session: session)
        }
    }

    /// 收掉这些。
    ///
    /// ⚠️ **两条路，别混。**
    ///  · 进程类：**先 TERM 再 KILL**，中间等 2 秒。Gradle 收到 TERM 会把缓存写完再退，
    ///    直接 -9 会留下坏掉的构建缓存。
    ///  · 会话类（`idle`）：**按会话名收，不按 pid 杀**。一个会话底下是
    ///    「shell → claude → 一堆子进程」，挨个 kill 会把 shell 杀在 claude 前头，
    ///    留下一个挂在 init 底下、内存照占的孤儿。`tmux kill-session` 一次收干净。
    ///
    /// ⚠️ **有 `cloud-forget` 就先用它。** remote-dev-station 那套机器上的 `cloud-watchdog`
    /// **每 15 秒把「登记过但没在跑」的会话 `claude --resume` 拉回来** ——
    /// 只 `tmux kill-session` 的话十几秒后原样复活，用户按半天以为没生效。
    /// `cloud-forget` 先移出恢复名单再杀，而且**保留对话存档**。没装那套的机器上退回 kill-session。
    ///
    /// ⚠️ pid 只从 `junk(from:)` 来 —— **不接受界面传任意数字**。
    /// ⚠️ **绝不生成杀 pid 1 的命令**（那是 init，杀了机器就没了）。
    public static func killCommand(_ junk: [Junk]) -> String? {
        var seen = Set<Int>()
        let pids = junk.filter { $0.session.isEmpty }.map(\.pid).filter { $0 > 1 && seen.insert($0).inserted }
        var seenName = Set<String>()
        let sessions = junk.map(\.session).filter { !$0.isEmpty && seenName.insert($0).inserted }
        guard !pids.isEmpty || !sessions.isEmpty else { return nil }
        var parts: [String] = []
        if !pids.isEmpty {
            let list = pids.map(String.init).joined(separator: " ")
            parts.append("kill -TERM \(list) 2>/dev/null; sleep 2; kill -KILL \(list) 2>/dev/null")
        }
        for name in sessions {
            let q = "'" + name.replacingOccurrences(of: "'", with: "'\\''") + "'"
            parts.append("if command -v cloud-forget >/dev/null 2>&1; then cloud-forget \(q) >/dev/null 2>&1; "
                + "else tmux kill-session -t \(q) 2>/dev/null; fi")
        }
        return parts.joined(separator: "; ") + "; true"
    }
}
