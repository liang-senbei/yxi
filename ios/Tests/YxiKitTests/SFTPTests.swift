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
