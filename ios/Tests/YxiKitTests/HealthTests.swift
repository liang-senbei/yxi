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
