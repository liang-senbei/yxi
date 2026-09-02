import XCTest
@testable import YxiKit

final class HealthTests: XCTestCase {

    /// ⚠️ **这是真机上抓的输出**（2026-08-29 那台卡住的服务器），不是编的。
    /// 编一份「格式看起来对」的样本，测的就只是我对格式的想象。
    /// ⚠️ 安卓端 `HealthTest.kt` 用的是**同一份**，期望值也一样 ——
    /// 两边任何一边算错都会在这里露出来。
    private let real = """
        #load
        8.93 10.00 24.35 5/1518 1102226
        #cpu
        16
        #mem
        MemTotal:       16372796 kB
        MemAvailable:    6518004 kB
        SwapTotal:       8388604 kB
        SwapFree:        2299196 kB
        #stat1
        cpu  64336943 41224 18218903 2063372825 7558482 0 2339734 187424529 9616286 0
        #stat2
        cpu  64337005 41224 18218947 2063374080 7558483 0 2339739 187425207 9616286 0
        #disk
        /dev/vda1        507930276 69471912 438441980      14% /
        """

    func testParsesRealOutput() {
        let v = Health.parse(real)!
        XCTAssertEqual(v.cores, 16)
        XCTAssertEqual(v.load1, 8.93, accuracy: 0.001)
        XCTAssertEqual(v.memUsedPct, 61)
        XCTAssertEqual(v.swapUsedPct, 72)
        XCTAssertEqual(v.diskUsedPct, 14)
    }

    /// ⚠️ **steal 是这个功能的核心** —— 也是当初诊断时漏掉的那个数。
    /// 算错了整个功能就白做（它会永远显示「一切正常」）。
    /// 33% 跟当时 `vmstat` 报的 35% 对得上（采样噪声内），安卓端也是 33。
    func testStealMatchesAndroid() {
        XCTAssertEqual(Health.parse(real)!.steal, 33)
    }

    /// 单次读 `/proc/stat` 只能算出「开机以来的平均」—— 拿不到两次就必须是 0，不能瞎猜。
    func testStealNeedsTwoSamples() {
        XCTAssertEqual(Health.steal("cpu  1 2 3 4 5 6 7 8", ""), 0)
        XCTAssertEqual(Health.steal("", ""), 0)
        let same = "cpu  1 2 3 4 5 6 7 8 9 0"
        XCTAssertEqual(Health.steal(same, same), 0)   // 除零要兜住
    }

    /// ⚠️ 体检报告里编一个数出来比不显示危险 —— 用户会照着它做决定。
    func testUnreadableReturnsNil() {
        XCTAssertNil(Health.parse(""))
        XCTAssertNil(Health.parse("bash: 什么鬼"))
        XCTAssertNil(Health.parse("#load\n\n#cpu\n16"))
    }

    func testStrainedMachineScoresBadly() {
        let r = Health.score(Health.parse(real)!)
        XCTAssertEqual(r.score, 45)                       // 安卓端也是 45
        XCTAssertEqual(Health.verdict(r.score), "strained")
        XCTAssertTrue(r.issues.contains { $0.code == "steal" })
        XCTAssertTrue(r.issues.contains { $0.code == "swap" })
    }

    /// ⚠️ steal 和磁盘满**不能标成 fixable** —— 一键收拾解决不了它们，
    /// 标错了用户按一次没反应只会更困惑。
    func testUnfixableNotMarkedFixable() {
        let r = Health.score(Health.parse(real)!)
        XCTAssertFalse(r.issues.first { $0.code == "steal" }!.fixable)
    }

    func testHealthyMachineIsPerfect() {
        let ok = Health.Vitals(cores: 16, load1: 1.0,
                               memTotalKb: 16_000_000, memAvailKb: 12_000_000,
                               swapTotalKb: 8_000_000, swapFreeKb: 8_000_000,
                               steal: 0, diskUsedPct: 20)
        let r = Health.score(ok)
        XCTAssertEqual(r.score, 100)
        XCTAssertTrue(r.issues.isEmpty)
        XCTAssertEqual(Health.verdict(r.score), "easy")
    }

    /// 没有 swap 分区的机器不能因为除零算出奇怪的数。
    func testNoSwapPartition() {
        let v = Health.Vitals(cores: 4, load1: 1.0, memTotalKb: 8_000_000, memAvailKb: 6_000_000,
                              swapTotalKb: 0, swapFreeKb: 0, steal: 0, diskUsedPct: 30)
        XCTAssertEqual(v.swapUsedPct, 0)
        XCTAssertEqual(Health.score(v).score, 100)
    }

    // ────────── 一键收拾 ──────────

    func testScanPicksOnlyWhitelisted() {
        let out = ["111\t3200000\t7200\tgradle",
                   "222\t120000\t900\trg",
                   "333\t50000\t120\tkotlin"].joined(separator: "\n")
        let j = Health.junk(from: out)
        XCTAssertEqual(j.map(\.pid), [111, 222, 333])
        XCTAssertEqual(Set(j.map(\.what)), ["gradle", "rg", "kotlin"])
    }

    /// 认不出的行直接跳过 —— **宁可少杀不可错杀**。
    func testGarbageIsNotATarget() {
        XCTAssertTrue(Health.junk(from: "乱七八糟").isEmpty)
        XCTAssertTrue(Health.junk(from: "abc\tdef\tghi\tjkl").isEmpty)
        XCTAssertTrue(Health.junk(from: "111\t222").isEmpty)
    }

    /// ⚠️ **绝不能生成杀 pid 1 的命令**（那是 init，杀了机器就没了）。
    func testNeverKillsInit() {
        XCTAssertNil(Health.killCommand([]))
        XCTAssertNil(Health.killCommand([.init(pid: 1, rssKb: 0, ageSec: 0, what: "gradle")]))
        let cmd = Health.killCommand([.init(pid: 1, rssKb: 0, ageSec: 0, what: "gradle"),
                                      .init(pid: 42, rssKb: 0, ageSec: 0, what: "rg")])!
        XCTAssertTrue(cmd.contains("42"))
        XCTAssertFalse(cmd.contains("kill -TERM 1 "))
    }

    /// ⚠️ 先 TERM 再 KILL：Gradle 收到 TERM 会把缓存写完再退，直接 -9 会留下坏缓存。
    func testTermBeforeKill() {
        let cmd = Health.killCommand([.init(pid: 42, rssKb: 0, ageSec: 0, what: "gradle")])!
        XCTAssertTrue(cmd.contains("kill -TERM"))
        XCTAssertTrue(cmd.contains("kill -KILL"))
        XCTAssertLessThan(cmd.range(of: "-TERM")!.lowerBound, cmd.range(of: "-KILL")!.lowerBound)
    }

    // ────────── 闲置会话 / 跑飞进程 ──────────

    /// ⚠️ **扫描命令必须把自己排除掉。**
    ///
    /// 原来那版栽在这儿：awk 的程序正文里写着 `/GradleDaemon/`，而 `ps -eo args=`
    /// 会把这条 awk 自己列出来 —— 它于是把自己认成 Gradle 守护进程。
    /// 用户手机上那句「可以收拾 1 类 · 约 7 MB」，收的就是它自己那几个临时 shell。
    func testScanExcludesItself() {
        XCTAssertTrue(Health.scanCommand.contains("yxiscan"), "自排除的记号没了")
        XCTAssertTrue(Health.scanCommand.contains("line ~ /yxiscan/) next"), "少了跳过自己那一行")
    }

    /// ⚠️⚠️ **「闲置」不许只看 tmux 的活动时间。**
    ///
    /// 那个值在没人 attach 时不更新 —— 一个正跑着的会话会显示「3 天没动」。
    /// 真事：按它清理，杀掉了用户正在用的 `cc-hexingyang`（tmux 说闲了 3.9 天，
    /// 转录 15 分钟前还在写）。这条断言钉住三件事，少一件这功能就会杀活人：
    ///  ① 要读转录的 mtime；② 按 sessionId 找转录（cd 之后 cwd 会漂）；
    ///  ③ Claude Code 报 busy / waiting 的一律跳过。
    func testIdleMustNotTrustTmuxActivityAlone() {
        let c = Health.scanCommand
        XCTAssertTrue(c.contains("projects"), "没去读转录的 mtime")
        XCTAssertTrue(c.contains("sessionId"), "没按 sessionId 找转录")
        XCTAssertTrue(c.contains("sessions"), "没读 Claude Code 自己的会话表")
        XCTAssertTrue(c.contains("busy"), "没排除正在忙的")
        XCTAssertTrue(c.contains("waiting"), "没排除正等你回答的")
        // 取两者较大的那个，不是只取 tmux 那个
        XCTAssertTrue(c.contains("if (f[2]+0 > last) last = f[2]+0"), "没取 max(tmux 活动, 转录 mtime)")
    }

    /// 会话那类多一个字段（会话名），别把它当坏行丢掉。
    func testSessionRowHasFiveFields() {
        let j = Health.junk(from: "0\t2100000\t1468800\tidle\tcc-文件")
        XCTAssertEqual(j.count, 1)
        XCTAssertEqual(j[0].what, "idle")
        XCTAssertEqual(j[0].session, "cc-文件")
        XCTAssertEqual(j[0].ageSec, 1468800)   // 「多久没动过」，不是「跑了多久」
    }

    /// ⚠️ 缺关键字段的行**整行丢掉**：没名字的 idle 会变成 `kill-session -t ''`，
    /// pid 是 0 的进程行更糟。
    func testRowsMissingFieldsAreDropped() {
        XCTAssertTrue(Health.junk(from: "0\t100\t100\tidle\t").isEmpty)
        XCTAssertTrue(Health.junk(from: "0\t100\t100\thog").isEmpty)
        XCTAssertTrue(Health.junk(from: "111\t100\t100\t").isEmpty)
    }

    /// ⚠️ **会话按名字收，不按 pid 杀** —— 挨个 kill 会把 shell 杀在 claude 前头，
    /// 留下占着内存的孤儿。而且必须先 `cloud-forget`，否则 watchdog 15 秒后原样拉回来。
    func testIdleSessionsGoThroughSessionPath() {
        let cmd = Health.killCommand([.init(pid: 0, rssKb: 2100000, ageSec: 1468800, what: "idle", session: "cc-文件")])!
        XCTAssertTrue(cmd.contains("cloud-forget"))
        XCTAssertTrue(cmd.contains("tmux kill-session"), "没装 cloud-forget 的机器要能退回")
        XCTAssertFalse(cmd.contains("kill -TERM"), "会话不该走 kill pid 那条路")
    }

    /// 会话名里带单引号不能把命令劈开。
    func testQuoteInSessionNameSurvives() {
        let cmd = Health.killCommand([.init(pid: 0, rssKb: 0, ageSec: 0, what: "idle", session: "it's")])!
        XCTAssertTrue(cmd.contains(#"'it'\''s'"#), "单引号没转义，命令会被劈开")
    }

    /// 两类混着勾：进程走 kill、会话走 forget，一条命令里都要有。
    func testProcessesAndSessionsTogether() {
        let cmd = Health.killCommand([
            .init(pid: 42, rssKb: 1000, ageSec: 7200, what: "gradle"),
            .init(pid: 0, rssKb: 2000, ageSec: 400000, what: "idle", session: "cc-旧的"),
        ])!
        XCTAssertTrue(cmd.contains("kill -TERM 42"))
        XCTAssertTrue(cmd.contains("cc-旧的"))
    }

    /// 只杀勾中的那几类 —— 界面按类别归堆勾选，传进来的必须是筛过的子集。
    func testOnlyPickedAreKilled() {
        let all = [Health.Junk(pid: 11, rssKb: 1000, ageSec: 7200, what: "gradle"),
                   Health.Junk(pid: 12, rssKb: 2000, ageSec: 7200, what: "gradle"),
                   Health.Junk(pid: 21, rssKb: 500, ageSec: 900, what: "rg")]
        let cmd = Health.killCommand(all.filter { $0.what == "gradle" })!
        XCTAssertTrue(cmd.contains("11") && cmd.contains("12"))
        XCTAssertFalse(cmd.contains("21"))
    }
}
