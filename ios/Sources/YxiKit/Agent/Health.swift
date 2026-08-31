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
        public let ageSec: Int64
        /// 代号：gradle / kotlin / rg（话术在 UI 层）
        public let what: String
    }

    /// 找出**可以安全收掉**的东西。
    ///
    /// ⚠️ **白名单，不是黑名单。** 只认这几类，别的一概不碰：
    ///  · **Gradle / Kotlin 编译守护进程** —— 构建缓存，杀了下次编译慢一点，什么都不会丢。
    ///  · **跑了 10 分钟以上的 `rg`** —— 编辑器的全盘搜索跑飞了。正常搜索几秒就完。
    ///
    /// ⚠️ **绝不碰** `claude`（用户的活）、`tmux`（杀了所有会话一起没）、
    /// `sshd`（杀了你自己就断线了）、编辑器本体。
    /// 一键修复要是能弄丢东西，它就不是「方便」，是陷阱。
    public static let scanCommand = """
        ps -eo pid=,rss=,etimes=,args= 2>/dev/null | awk '
          {
            pid=$1; rss=$2; age=$3
            line=""; for (i=4; i<=NF; i++) line = line $i " "
            what=""
            if (line ~ /GradleDaemon/)            what="gradle"
            else if (line ~ /KotlinCompileDaemon/) what="kotlin"
            else if (line ~ /(^|\\/)rg( |$)/ && age > 600) what="rg"
            if (what != "") printf "%s\\t%s\\t%s\\t%s\\n", pid, rss, age, what
          }'
        """

    /// 读 `scanCommand` 的输出。认不出的行直接跳过 —— **宁可少杀不可错杀**。
    public static func junk(from out: String) -> [Junk] {
        out.split(separator: "\n").compactMap { line in
            let p = line.split(separator: "\t", omittingEmptySubsequences: false)
            guard p.count >= 4, let pid = Int(p[0].trimmingCharacters(in: .whitespaces)) else { return nil }
            return Junk(pid: pid,
                        rssKb: Int64(p[1].trimmingCharacters(in: .whitespaces)) ?? 0,
                        ageSec: Int64(p[2].trimmingCharacters(in: .whitespaces)) ?? 0,
                        what: p[3].trimmingCharacters(in: .whitespaces))
        }
    }

    /// 收掉这些。
    ///
    /// ⚠️ **先 TERM 再 KILL**，中间等 2 秒：Gradle 收到 TERM 会把缓存写完再退，
    /// 直接 -9 会留下坏掉的构建缓存，下次编译报一堆莫名其妙的错。
    /// ⚠️ pid 只从 `junk(from:)` 来 —— **不接受界面传任意数字**，
    /// 免得哪天改 UI 时把一个能杀任何进程的口子留在那儿。
    /// ⚠️ **绝不生成杀 pid 1 的命令**（那是 init，杀了机器就没了）。
    public static func killCommand(_ junk: [Junk]) -> String? {
        var seen = Set<Int>()
        let pids = junk.map(\.pid).filter { $0 > 1 && seen.insert($0).inserted }
        guard !pids.isEmpty else { return nil }
        let list = pids.map(String.init).joined(separator: " ")
        return "kill -TERM \(list) 2>/dev/null; sleep 2; kill -KILL \(list) 2>/dev/null; true"
    }
}
