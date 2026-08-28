import XCTest
@testable import YxiKit

/// `SSHSession.follow` 生成的那个保活外壳 —— **真的把它跑起来**验，不看字符串。
///
/// ⚠️ 这条测试存在的理由：外壳原来不查被包起来的进程死没死，
/// 于是命令挂了通道还开着，上层 `for await` 永不返回、兜底轮询成死代码。
/// 表现是待答卡片永远不出现**且不报错** —— 对「手机远程审批」是最坏的失败形态。
#if os(Linux) || os(macOS)
final class FollowShellTests: XCTestCase {

    private func run(_ inner: String, timeout: TimeInterval) -> Int32? {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/bin/bash")
        p.arguments = ["-c", SSHSession.follow(inner)]
        p.standardOutput = Pipe()
        p.standardError = Pipe()
        try? p.run()
        let deadline = Date().addingTimeInterval(timeout)
        while p.isRunning && Date() < deadline { usleep(100_000) }
        if p.isRunning { p.terminate(); return nil }   // nil = 还活着
        return p.terminationStatus
    }

    /// 被包的命令立刻失败 → 外壳必须**跟着退出**，不能继续吐心跳
    func testExitsWhenInnerCommandDies() {
        let code = run("false", timeout: 8)
        XCTAssertNotNil(code, "内层已经死了，外壳还在跑 —— 通道永远不关，兜底轮询进不去")
    }

    /// 被包的命令还活着 → 外壳必须**继续跑**（否则盯屏一起来就断）
    func testKeepsRunningWhileInnerAlive() {
        XCTAssertNil(run("sleep 30", timeout: 6), "内层还活着，外壳却退出了")
    }

    /// 心跳频率不能变：查活 2 秒一次，心跳仍是 20 秒一次
    func testHeartbeatStillTwentySeconds() {
        let cmd = SSHSession.follow("x")
        XCTAssertTrue(cmd.contains("sleep 2;"), cmd)
        XCTAssertTrue(cmd.contains("-ge 10"), "2 秒 × 10 = 20 秒心跳")
        XCTAssertTrue(cmd.contains("kill -0"), "缺探活")
    }
}
#endif
