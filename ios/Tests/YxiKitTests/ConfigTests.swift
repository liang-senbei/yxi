import XCTest
@testable import YxiKit

// MARK: - 写回前的把关

final class ConfigValidationTests: XCTestCase {

    func testGoodJSONPasses() {
        XCTAssertNil(AgentConfig.validationError(path: "/a/settings.json", text: #"{"model":"opus"}"#))
    }

    /// 这条是这个函数存在的**唯一理由**：多一个逗号 = Claude Code 起不来
    func testTrailingCommaRejected() {
        let why = AgentConfig.validationError(path: "/a/settings.json", text: #"{"model":"opus",}"#)
        XCTAssertNotNil(why)
        XCTAssertTrue(why!.hasPrefix("JSON 格式不对"))
    }

    func testEmptyRejected() {
        XCTAssertNotNil(AgentConfig.validationError(path: "/a/settings.json", text: "   \n "))
    }

    /// toml / md 没有便宜的校验器，不能因此拦着不让存
    func testNonJSONAlwaysPasses() {
        XCTAssertNil(AgentConfig.validationError(path: "/a/config.toml", text: "这里随便写 [[[["))
        XCTAssertNil(AgentConfig.validationError(path: "/a/CLAUDE.md", text: ""))
    }

    /// 备份必须在写之前发生，且带时间戳 —— 名字撞了就等于没备份
    func testBackupCommandShape() {
        let cmd = AgentConfig.backupCommand("/root/.claude/settings.json", stamp: 1735000000)
        XCTAssertTrue(cmd.contains("'/root/.claude/settings.json'"))
        XCTAssertTrue(cmd.contains(".yxi-bak-1735000000"))
        XCTAssertTrue(cmd.hasPrefix("cp "))
    }

    /// 路径里有单引号也不能把命令拼断（`它'的.json`）
    func testQuotingSurvivesApostrophe() {
        let cmd = AgentConfig.backupCommand("/a/it's.json", stamp: 1)
        XCTAssertTrue(cmd.contains(#"'/a/it'\''s.json'"#), cmd)
    }
}

/// 尾逗号那条自己写的扫描 —— 边界比想象中多
final class TrailingCommaTests: XCTestCase {

    func testCommaInsideStringIsNotTrailing() {
        // 字符串里的逗号不算数，否则这份完全合法的配置会被拒
        XCTAssertNil(AgentConfig.validationError(
            path: "a.json", text: #"{"tip":"先 a, 再 b","n":1}"#))
    }

    func testEscapedQuoteDoesNotBreakScanner() {
        XCTAssertNil(AgentConfig.validationError(
            path: "a.json", text: #"{"q":"他说\"好,\"","n":[1,2]}"#))
    }

    func testTrailingCommaInArray() {
        XCTAssertNotNil(AgentConfig.validationError(path: "a.json", text: #"{"n":[1,2,]}"#))
    }

    func testTrailingCommaAcrossNewline() {
        let why = AgentConfig.validationError(path: "a.json", text: "{\n  \"a\": 1,\n}\n")
        XCTAssertNotNil(why)
        XCTAssertTrue(why!.contains("第 2 行"), why ?? "")
    }

    func testNestedCloseAfterValueIsFine() {
        XCTAssertNil(AgentConfig.validationError(path: "a.json", text: #"{"a":{"b":[1,{"c":2}]}}"#))
    }
}
