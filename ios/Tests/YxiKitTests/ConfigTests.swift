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

/// ⚠️ `base64 -w0` 是 GNU 的；BSD/macOS 不认。两条路都要有，
/// 否则服务器一是 macOS，实验室的图就全空白**而且不报错**。
final class LabBytesCommandTests: XCTestCase {
    func testHasBothGnuAndBsdForms() {
        let c = Lab.bytesCommand("a.png")
        XCTAssertTrue(c.contains("base64 -w0"), c)
        XCTAssertTrue(c.contains("||"), "缺少 BSD 退路：\(c)")
        XCTAssertTrue(c.contains("tr -d"), "BSD 那条要自己去掉换行：\(c)")
    }
    /// 文件名只留安全字符 —— manifest 是我们自己写的，但仍不给注入留口子。
    /// ⚠️ 断言要盯**文件名那一段**，不能整条命令去找 `$`：
    /// 目录本来就是 `$HOME/.yxi/lab`，整条找必然命中，等于没测。
    /// （上一版就是这么写的，于是真 bug 来了它报警，我却以为是断言太宽。）
    func testStripsUnsafeChars() {
        XCTAssertFalse(Lab.bytesCommand("a;rm -rf /.png").contains(";"))
        XCTAssertFalse(Lab.bytesCommand("a$(id).png").contains("$(id)"))
        XCTAssertFalse(Lab.bytesCommand("a`id`.png").contains("`"))
    }

    /// ⚠️ **`$HOME` 必须能展开。** 加了单引号就成了字面量目录名，
    /// 图片和 GIF 全部空白且不报错（`2>/dev/null` 连话都吞了）。
    func testHomeIsNotQuoted() {
        let c = Lab.bytesCommand("a.png")
        XCTAssertFalse(c.contains("'$HOME"), "单引号挡住了 $HOME 展开：\(c)")
        XCTAssertTrue(c.contains("$HOME/.yxi/lab/a.png"), c)
    }
}

/// 备份这条路是**唯一能破坏服务器状态**的写路径，把关要硬。
final class BackupCommandTests: XCTestCase {

    /// ⚠️ `|| true` 让命令恒定成功 → 界面永远说「已备份」，哪怕根本没备成
    func testNoUnconditionalTrue() {
        let c = AgentConfig.backupCommand("/a/b.json", stamp: 1)
        XCTAssertFalse(c.contains("|| true"), "恒真的命令等于没有校验：\(c)")
        XCTAssertTrue(c.contains(AgentConfig.backupOK), "成功时必须有可判定的回执：\(c)")
        XCTAssertTrue(c.contains("[ -f"), "得真去看备份文件在不在：\(c)")
    }

    /// 真的跑一遍：备成了吐回执，备不成不吐
    #if os(Linux) || os(macOS)
    func testActuallyBacksUp() throws {
        let dir = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("yxi-bak-test-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let file = dir.appendingPathComponent("conf.json")
        try "{}".write(to: file, atomically: true, encoding: .utf8)

        func sh(_ c: String) -> String {
            let p = Process(); let pipe = Pipe()
            p.executableURL = URL(fileURLWithPath: "/bin/bash")
            p.arguments = ["-c", c]; p.standardOutput = pipe; p.standardError = Pipe()
            try? p.run(); p.waitUntilExit()
            return String(decoding: pipe.fileHandleForReading.readDataToEndOfFile(), as: UTF8.self)
        }

        XCTAssertTrue(sh(AgentConfig.backupCommand(file.path, stamp: 7))
            .contains(AgentConfig.backupOK), "文件在，应该备份成功")
        XCTAssertTrue(FileManager.default.fileExists(
            atPath: file.path + ".yxi-bak-7"), "备份文件没生成")

        // 源文件不存在 → 必须**不吐**回执（旧写法这里也会说成功）
        XCTAssertFalse(sh(AgentConfig.backupCommand(dir.appendingPathComponent("nope.json").path,
                                                    stamp: 8)).contains(AgentConfig.backupOK))
    }
    #endif
}

/// 顶层必须是对象/数组 —— 裸值一样能让 Claude Code 起不来
final class JSONTopLevelTests: XCTestCase {
    func testFragmentsRejected() {
        for bad in ["123", "\"hello\"", "null", "true"] {
            XCTAssertNotNil(AgentConfig.validationError(path: "a.json", text: bad), bad)
        }
    }
    func testObjectAndArrayPass() {
        XCTAssertNil(AgentConfig.validationError(path: "a.json", text: "{}"))
        XCTAssertNil(AgentConfig.validationError(path: "a.json", text: "[1,2]"))
    }
}
