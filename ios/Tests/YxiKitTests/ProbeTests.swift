import XCTest
@testable import YxiKit

/// 会话快照 / 用量 / 更新清单。全部用真机上跑出来的原样输出。
final class ProbeTests: XCTestCase {

    // MARK: - 一次往返拿全部

    func test_一次往返解出全部会话() {
        let s = SessionProbe.parseSnapshot(Fixture.snapshot)
        XCTAssertFalse(s.isEmpty)
        let byName = Dictionary(uniqueKeysWithValues: s.map { ($0.name, $0) })

        let yxi = byName["cc-Yxi"]
        XCTAssertEqual(yxi?.cwd, "/root/src/workspace/Yxi")
        XCTAssertEqual(yxi?.attached, true)
        XCTAssertEqual(yxi?.short, "Yxi", "看板上显示的是去掉 cc- 的短名")

        // `cc-state` 的字符串要映射成语义状态
        XCTAssertEqual(byName["cc-livetest"]?.state, .needsYou, "cc-state 的 input = 等你")
        XCTAssertEqual(byName["cc-Anthropic-Inspector"]?.state, .working)
        XCTAssertEqual(byName["cc-perm"]?.state, .idle)
        XCTAssertEqual(byName["cc-livetest"]?.detail, "Claude is waiting for your input")

        // ⚠️ 中文会话名要原样活下来（`tmux list-sessions` 的分隔符是 `|`，中文不受影响）
        XCTAssertEqual(byName["cc-日常对话"]?.cwd, "/opt/workspace/日常对话")
    }

    /// ⚠️ 三种真实的脏数据，一个都不能崩，也不能造出幽灵行：
    ///   · `~/.cloud-status/` 下真的躺着内容是 `{}` 的文件（没有 `session` 字段）
    ///   · 有些 json 对应的 tmux 会话早就没了（`cc-tmp-035858-6849` 之类）
    ///   · 状态里的 `detail` 带引号和转义
    func test_脏状态文件不造幽灵行() {
        let s = SessionProbe.parseSnapshot(Fixture.snapshot)
        // 会话数只由 tmux 那段决定，状态那段只能给它们贴标签
        let tmuxLines = Fixture.snapshot
            .components(separatedBy: "\n")
            .filter { $0.contains("|") && !$0.contains(SessionProbe.marker) }
        XCTAssertEqual(s.count, tmuxLines.count, "多出来的行是状态文件造出来的幽灵")
        XCTAssertFalse(s.contains { $0.name.contains("cc-tmp-") }, "早没了的会话不该出现")
    }

    /// 缺段就当空，不抛异常 —— marker 分段的意义就在这（主机侧脚本改了、App 还是旧的）。
    func test_缺段不崩() {
        XCTAssertTrue(SessionProbe.parseSnapshot("").isEmpty)
        XCTAssertTrue(SessionProbe.parseSnapshot("完全不相干的输出").isEmpty)
    }

    /// ⚠️ 白名单，因为这个字符串最终会拼进 shell 命令、
    /// 而且会变成**打进别人服务器**的按键。不在白名单里就什么都别做。
    func test_按键白名单() {
        XCTAssertNotNil(SessionProbe.keyCommand(target: "cc-Yxi", key: "3"))
        XCTAssertNotNil(SessionProbe.keyCommand(target: "cc-Yxi", key: "Right"))
        XCTAssertNil(SessionProbe.keyCommand(target: "cc-Yxi", key: "rm -rf /"))
        XCTAssertNil(SessionProbe.keyCommand(target: "cc-Yxi", key: "'; rm -rf / #"))
        XCTAssertNil(SessionProbe.keyCommand(target: "cc-Yxi", key: ""))
        XCTAssertNil(SessionProbe.keyCommand(target: "cc-Yxi", key: "Enter Enter"))
    }

    /// ⚠️ **发消息必须分两步：先送文本、再单独送回车。**
    /// 合成一条时，文本里若含特殊字符会让 `send-keys` 把它当**按键名**解析 ——
    /// 比如 `Enter` 这三个字就会变成一次回车，用户的半句话会被当场发出去。
    func test_发消息分两步且转义单引号() {
        let cmds = SessionProbe.sendCommands(target: "cc-Yxi", text: "别把 Enter 当按键 it's fine")
        XCTAssertEqual(cmds.count, 2)
        XCTAssertTrue(cmds[0].contains(" -l "), "第一条是字面文本")
        XCTAssertTrue(cmds[0].contains(#"it'\''s"#), "单引号要转义，否则命令被截断")
        XCTAssertEqual(cmds[1], "tmux send-keys -t 'cc-Yxi' Enter")
    }

    // MARK: - 用量

    /// 真机上 `ccusage blocks --active --json` 的原样输出。
    func test_活动窗口的用量() {
        guard let u = Usage.parse(Fixture.ccusageActive) else { return XCTFail("活动块没解出来") }
        XCTAssertGreaterThan(u.remainingMinutes, 0)
        XCTAssertLessThanOrEqual(u.remainingMinutes, 300, "5 小时窗口 = 300 分钟")
        XCTAssertEqual(u.elapsedPercent, (300 - u.remainingMinutes) * 100 / 300)
        XCTAssertGreaterThan(u.tokens, 0)
        XCTAssertGreaterThan(u.costUSD, 0)
        XCTAssertGreaterThan(u.tokensPerMinute, 0, "burnRate 在活动块里是对象不是 null")
        XCTAssertTrue(u.tokenText.hasSuffix("M"), "上百万要显示成 xx.xM：\(u.tokenText)")
        XCTAssertTrue(u.remainText.contains("h"), "\(u.remainText)")
    }

    /// ⚠️ 防 TROUBLESHOOTING #51：**宁可整块不显示，也不能显示一个假的。**
    /// 下面三种都必须是 nil —— 显示 0 会让人以为「额度马上重置」，
    /// 然后照着那个数字安排今天开不开大活。
    func test_拿不到就返回nil而不是零() {
        // 当前 5 小时窗口空闲（station 上真实的输出）
        XCTAssertNil(Usage.parse(Fixture.ccusageEmpty))
        // 没装 ccusage —— probeCommand 里 `command -v … || exit 0`，输出是空的
        XCTAssertNil(Usage.parse(""))
        XCTAssertNil(Usage.parse("\n"))
        // 命令没走通，输出的不是 JSON
        XCTAssertNil(Usage.parse("bash: ccusage: command not found"))
        // ⚠️ 非活动块的 `projection` 是 **null**（不是缺字段）。拿不到「还剩多少分钟」
        // 就整块不显示 —— 硬写成 0h00m 就是那个「假的」。
        XCTAssertNil(Usage.parse(Fixture.ccusageInactive))
    }

    // MARK: - 更新清单

    /// 服务器上真实的 `~/.yxi/latest.json`。
    func test_清单里有更新() {
        guard case let .newer(u) = Update.parse(manifest: Fixture.latestJson, currentCode: 1) else {
            return XCTFail("应该认出有新版本")
        }
        XCTAssertGreaterThan(u.versionCode, 1)
        XCTAssertFalse(u.versionName.isEmpty)
        XCTAssertTrue(u.remotePath.hasPrefix("/root/.yxi/"), "相对文件名要拼成绝对路径")
        XCTAssertFalse(u.notes.isEmpty)
    }

    /// ⚠️ **「没查到」和「已是最新」必须分开。**
    /// 把连不上说成「已是最新」是在骗用户 —— 他会以为自己是最新版，
    /// 而实际上可能落后好几版、正带着已知的 bug 在用。
    func test_已是最新和读不到是两回事() {
        XCTAssertEqual(Update.parse(manifest: Fixture.latestJson, currentCode: 9999), .upToDate)
        guard case .failed = Update.parse(manifest: "", currentCode: 1) else {
            return XCTFail("读不到不能说成「已是最新」")
        }
        guard case .failed = Update.parse(manifest: "<html>404</html>", currentCode: 1) else {
            return XCTFail("格式不对不能说成「已是最新」")
        }
    }

    /// ⚠️ 清单说有新版本、但包不在，就**当没有** —— 别让用户点一个必然失败的按钮。
    /// （这个坑真出过：发布脚本写的是两个不同的文件名，清单更新了包没换，
    /// 下载链接一直在发旧包，TROUBLESHOOTING #69。）
    func test_包不在就不给下载按钮() {
        guard case let .newer(u) = Update.parse(manifest: Fixture.latestJson, currentCode: 1) else {
            return XCTFail("先得认出有新版本")
        }
        guard case let .failed(why) = Update.confirm(u, sizeBytes: 0) else {
            return XCTFail("包不在还给了下载按钮")
        }
        XCTAssertTrue(why.contains(u.remotePath), "报错要说清是哪个文件不在：\(why)")
        guard case let .newer(ok) = Update.confirm(u, sizeBytes: 33_415_302) else {
            return XCTFail("包在的时候要给")
        }
        XCTAssertEqual(ok.sizeText, "31.9 MB")
    }

    // MARK: - 转录文件的定位

    func test_找转录文件的命令和结果() {
        let cmd = TranscriptStream.latestCommand(cwd: "/opt/workspace/日常对话")
        XCTAssertTrue(cmd.contains("-opt-workspace-----"), "cwd 要先归一成项目目录名：\(cmd)")
        XCTAssertEqual(
            TranscriptStream.parseLatest("/root/.claude/projects/-tmp/abc.jsonl\n"),
            "/root/.claude/projects/-tmp/abc.jsonl"
        )
        // 这个会话里没跑过 Claude Code —— 必须是 nil，对话模式据此置灰**并说明原因**
        XCTAssertNil(TranscriptStream.parseLatest(""))
        XCTAssertNil(TranscriptStream.parseLatest("ls: 没有那个文件或目录\n"))
    }
}
