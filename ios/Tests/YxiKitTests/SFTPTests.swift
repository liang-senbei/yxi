import XCTest
@testable import YxiKit

// MARK: - 文件大小

final class HumanSizeTests: XCTestCase {
    func testBoundaries() {
        XCTAssertEqual(humanSize(0), "0 B")
        XCTAssertEqual(humanSize(1023), "1023 B")
        XCTAssertEqual(humanSize(1024), "1 K")
        XCTAssertEqual(humanSize(1024 * 1024 - 1), "1024 K")
        XCTAssertEqual(humanSize(1024 * 1024), "1.0 M")
        XCTAssertEqual(humanSize(1024 * 1024 * 1024), "1.0 G")
    }
    /// 安卓那份对 5.5M 显示 "5.5 M" —— 两端必须一致，否则同一个文件两个数
    func testMatchesAndroid() {
        XCTAssertEqual(humanSize(5_767_168), "5.5 M")
        XCTAssertEqual(humanSize(2048), "2 K")
    }
}

// MARK: - git diff

final class GitDiffCommandTests: XCTestCase {
    func testQuotesPathWithApostrophe() {
        let c = SessionProbe.gitDiffCommand(cwd: "/home/it's/repo")
        XCTAssertTrue(c.contains(#"cd '/home/it'\''s/repo'"#), c)
    }
    /// 两种「没东西看」的情况都必须有话说，不能是空白
    func testAlwaysSaysSomething() {
        let c = SessionProbe.gitDiffCommand(cwd: "/x")
        XCTAssertTrue(c.contains("（没有未提交的改动）"))
        XCTAssertTrue(c.contains("（这里不是 git 仓库）"))
    }
    func testCapsOutput() {
        XCTAssertTrue(SessionProbe.gitDiffCommand(cwd: "/x").contains("head -c 60000"))
    }
}

final class AgoTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_800_000_000)
    private func at(_ secondsAgo: Int) -> Date { now.addingTimeInterval(-Double(secondsAgo)) }

    func testBuckets() {
        XCTAssertEqual(ago(at(5), now: now), "刚刚")
        XCTAssertEqual(ago(at(59), now: now), "刚刚")
        XCTAssertEqual(ago(at(60), now: now), "1 分钟前")
        XCTAssertEqual(ago(at(3599), now: now), "59 分钟前")
        XCTAssertEqual(ago(at(3600), now: now), "1 小时前")
        XCTAssertEqual(ago(at(86400), now: now), "1 天前")
        XCTAssertEqual(ago(at(86400 * 40), now: now), "很久以前")
    }

    /// ⚠️ 服务器时钟可能比手机快一点点 —— 别显示成「-3 秒前」
    func testFutureIsNotNegative() {
        XCTAssertEqual(ago(now.addingTimeInterval(30), now: now), "刚刚")
    }
}
