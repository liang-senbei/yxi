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

    /// ⚠️ **发消息必须是两条 `send-keys`：先送文本、再单独送回车。**
    /// 合成**一条 send-keys** 时，文本里若含特殊字符会让它当**按键名**解析 ——
    /// 比如 `Enter` 这三个字就会变成一次回车，用户的半句话会被当场发出去。
    ///
    /// ⚠️⚠️ **而且两者之间必须隔一下。** Claude Code 的输入框认「括号粘贴」：
    /// 一大块文本连着来按粘贴处理，粘贴块里的换行是**字面换行不是提交**，
    /// 紧跟着的 Enter 被算进那一块 —— 整段话躺在输入框里没发出去。
    /// `server/yxi-hub` 上真栽过：agent 之间发的长消息全卡在对方输入框里。
    /// ⚠️ 单行不触发（不够长，不当粘贴），所以手测「你好」是测不出来的。
    func test_发消息两条send_keys中间要隔一下() {
        let cmds = SessionProbe.sendCommands(target: "cc-Yxi", text: "别把 Enter 当按键 it's fine")
        XCTAssertEqual(cmds.count, 1, "现在合成一条 shell 命令，一个来回")
        let c = cmds[0]
        XCTAssertTrue(c.contains(" -l "), "文本要走字面")
        XCTAssertTrue(c.contains(#"it'\''s"#), "单引号要转义，否则命令被截断")
        XCTAssertTrue(c.contains("sleep"), "少了停顿，多行消息会卡在对方输入框里")
        // 顺序：文本 → sleep → Enter，一步都不能挪
        let iText = c.range(of: " -l ")!.lowerBound
        let iSleep = c.range(of: "sleep")!.lowerBound
        // ⚠️ 从后往前找 —— 正文里就有「Enter」两个字（这条测试的样例文本故意带着它），
        // 从前往后找会找到正文里那个，断言就永远是假的
        let iEnter = c.range(of: "Enter", options: .backwards)!.lowerBound
        XCTAssertTrue(iText < iSleep && iSleep < iEnter, "顺序错了：\(c)")
        XCTAssertTrue(c.contains("send-keys -t 'cc-Yxi' Enter"), "回车仍是单独一条 send-keys")
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

    /// ⚠️⚠️ **转录要按 sessionId 找，不能只按目录找。**
    ///
    /// Claude Code 的转录目录是拿**启动时**那个目录名拼的，`pane_current_path`
    /// 是**此刻**的目录 —— 会话里 `cd` 一下，两者永久对不上。真事：
    /// `cc-hexingyang` 在 `/root/src/workspace/hexingyang` 启动、后来 cd 进子目录，
    /// 对话页从此一直说「没找到转录」，而转录一直在原目录里写着。
    func test_转录按sessionId找() {
        let cmd = TranscriptStream.latestCommand(
            cwd: "/root/src/workspace/hexingyang/unitree_rl_mjlab-main", session: "cc-hexingyang")
        XCTAssertTrue(cmd.contains("sessions"), "没去读 Claude Code 自己的会话表：\(cmd)")
        XCTAssertTrue(cmd.contains("sessionId"), "没按 sessionId 找：\(cmd)")
        XCTAssertTrue(cmd.contains("cc-hexingyang"), "会话名没带进去：\(cmd)")
        // ⚠️ 找不到还得能退回按目录找（没有会话表的机器）
        XCTAssertTrue(cmd.contains("-root-src-workspace-hexingyang-unitree-rl-mjlab-main"),
                      "没有退回按目录找那条：\(cmd)")
        // ⚠️ 通配符只能一层 —— 递归会挑到 `<会话uuid>/subagents/` 里子 agent 那份
        XCTAssertFalse(cmd.contains("**"), "不许递归找")
    }

    /// ⚠️ 终止必须优先走 `cloud-forget`：光 kill-session 十几秒后 watchdog 就把会话拉回来，
    /// 用户看到的是「终止了还在」。没装的机器要能退回 kill-session。
    func test_终止要先移出自动恢复名单() {
        let c = SessionProbe.killCommand(session: "cc-begirl")
        XCTAssertTrue(c.contains("cloud-forget 'cc-begirl'"), "没走 cloud-forget：\(c)")
        XCTAssertTrue(c.contains("tmux kill-session -t 'cc-begirl'"), "没有退路：\(c)")
        XCTAssertLessThan(c.range(of: "cloud-forget")!.lowerBound, c.range(of: "kill-session")!.lowerBound,
                          "cloud-forget 得在前面")
        XCTAssertTrue(SessionProbe.killCommand(session: "it's").contains(#"'it'\''s'"#), "单引号要转义")
    }

    /// 不给会话名时保持老行为（按目录找），别把没传名字的调用点弄坏。
    func test_没有会话名时退回按目录找() {
        let cmd = TranscriptStream.latestCommand(cwd: "/opt/workspace/日常对话")
        XCTAssertFalse(cmd.contains("sessionId"), "没会话名就不该去查会话表")
        XCTAssertTrue(cmd.contains("-opt-workspace-----"))
    }

    /// ⚠️ **`isMeta` 的消息不是用户打的，绝不能画成用户气泡。**
    /// 用户报的就是这个：他从没打过那句 `[Image: original …]`，手机上却是个蓝气泡。
    func test_图片注解不冒充用户说的话() {
        let img = #"[Image: original 1264x2800, displayed at 903x2000. Multiply coordinates by 1.40 to map to original image.]"#
        let line = #"{"type":"user","uuid":"u1","isMeta":true,"message":{"role":"user","content":"\#(img)"}}"#
        XCTAssertTrue(Transcript.parse([line]).isEmpty, "图片坐标注解该整条丢掉")

        // 其余 isMeta 当系统消息画，不混进用户说的话里
        let hook = #"{"type":"user","uuid":"u2","isMeta":true,"message":{"role":"user","content":"Stop hook feedback: 还没做完"}}"#
        let items = Transcript.parse([hook])
        XCTAssertEqual(items.count, 1)
        if case .user = items[0] { XCTFail("isMeta 被画成用户气泡了") }

        // 真是用户打的照旧
        let real = #"{"type":"user","uuid":"u3","message":{"role":"user","content":"帮我看看这个"}}"#
        if case .user = Transcript.parse([real])[0] {} else { XCTFail("真用户消息不该被吞") }
    }
}
