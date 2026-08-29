import XCTest
@testable import YxiKit

/// 这一批是 2026-08-28 从安卓版补齐过来的功能，测试也照着安卓那边的用例写，
/// **两端对同一份判据负责**。
final class NewFeatureTests: XCTestCase {

    // MARK: 工单（对应安卓 TicketsTest）

    func test_工单_单引号要转义() {
        XCTAssertEqual(Tickets.shellSingleQuote("abc"), "'abc'")
        XCTAssertEqual(Tickets.shellSingleQuote("it's"), #"'it'\''s'"#)
    }

    func test_工单_别的危险字符靠单引号本身挡住() {
        let nasty = "$HOME `rm -rf /` ; echo x | tee \\ && ok"
        let q = Tickets.shellSingleQuote(nasty)
        XCTAssertTrue(q.hasPrefix("'") && q.hasSuffix("'"))
        XCTAssertEqual(String(q.dropFirst().dropLast()), nasty)   // 原样保留
    }

    func test_工单_解析跳过坏行和空文本() {
        let raw = """
        {"at":100,"text":"第一条","version":"0.9.27(71)"}
        这不是 json
        {"at":200,"text":"第二条"}
        {"at":300,"text":""}
        """
        let t = Tickets.parse(raw)
        XCTAssertEqual(t.count, 2)
        XCTAssertEqual(t.first?.text, "第二条")     // 最新的在前
        XCTAssertEqual(t.last?.version, "0.9.27(71)")
    }

    // MARK: 上次活动时间（对应安卓 ActivityTest / TROUBLESHOOTING #130）

    func test_转录比tmux新就用转录的() {
        let trs = ["-root-src-workspace-claude-desktop": 1787762900.0]
        XCTAssertEqual(
            SessionProbe.lastActivityOf(tmuxTs: 1787582123, cwd: "/root/src/workspace/claude_desktop", transcripts: trs),
            1787762900)
    }

    func test_读不到转录就退回tmux() {
        XCTAssertEqual(
            SessionProbe.lastActivityOf(tmuxTs: 1787582123, cwd: "/some/other/dir", transcripts: [:]),
            1787582123)
    }

    func test_解析转录时间表() {
        let m = SessionProbe.parseTranscriptTimes("-a\t100\n坏行\n-b\tnot-a-number\n-c\t0\n-d\t300\n")
        XCTAssertEqual(m.count, 2)
        XCTAssertEqual(m["-a"], 100)
        XCTAssertEqual(m["-d"], 300)
    }

    // MARK: 盯屏推流（TROUBLESHOOTING #134）

    func test_盯屏命令带自退出和标记() {
        let c = SessionProbe.watchScreenCommand(target: "cc-Yxi")
        XCTAssertTrue(c.contains("cc-Yxi"))
        XCTAssertTrue(c.contains(SessionProbe.screenMarker))
        XCTAssertTrue(c.contains("|| exit"), "会话没了要让远端循环自己退，否则留一堆空转的壳")
        XCTAssertTrue(c.contains("prev"), "服务器侧要自己比对，没变不过网")
    }

    // MARK: 模式与常用语

    func test_模式默认值是核过的形式() {
        let cmds = Modes.defaults.map(\.command)
        // ⚠️ /model opus[1m] 实测不认，必须全名带后缀
        XCTAssertTrue(cmds.contains("/model claude-opus-5[1m]"))
        XCTAssertTrue(cmds.contains("/effort max"))
    }

    func test_模式编解码往返() {
        let round = Modes.decode(Modes.encode(Modes.defaults))
        XCTAssertEqual(round, Modes.defaults)
        XCTAssertEqual(Modes.decode("乱七八糟没有竖线"), Modes.defaults)   // 解不出就回默认
    }

    func test_常用语解码去空行() {
        XCTAssertEqual(Snippets.decode("继续\n\n  好的  \n"), ["继续", "好的"])
        XCTAssertEqual(Snippets.decode("   \n "), Snippets.defaults)
    }

    // MARK: 实验室

    func test_实验室_解析manifest并归类() {
        let raw = """
        [{"id":"a1","title":"雪山","type":"image","file":"a1.jpg","by":"Gemini","at":1787646917},
         {"id":"a2","title":"动图","type":"gif","file":"a2.gif"},
         {"id":"a3","title":"随手记","type":"note","file":""}]
        """
        let items = Lab.parse(manifest: raw)
        XCTAssertEqual(items.count, 3)
        XCTAssertEqual(items[0].catKey, "image")
        XCTAssertEqual(Lab.categoryName(items[0].catKey), "图像")
        XCTAssertEqual(items[0].by, "Gemini")
        XCTAssertEqual(Lab.categoryName(items[1].catKey), "动图")
        XCTAssertEqual(Lab.categoryName(items[2].catKey), "文字")
    }

    func test_实验室_文件名只留安全字符() {
        // 不能让 manifest 里的文件名跑出目录或注入命令
        let c = Lab.textCommand("../../etc/passwd; rm -rf /")
        XCTAssertFalse(c.contains(";"))
        XCTAssertFalse(c.contains(" rm"))
    }

    // MARK: 公网更新

    func test_公网更新走https且不带token() {
        XCTAssertTrue(Update.publicBase.hasPrefix("https://"))
        XCTAssertNotNil(Update.publicManifestURL)
        XCTAssertFalse(Update.publicBase.contains("64.90.25.56"))
    }
}

/// 排队气泡的进出。**每条都对应一个「气泡永远不消失」或「消息真丢了」的场景。**
final class QueueOperationTests: XCTestCase {

    private func line(_ op: String, _ content: String? = nil) -> String {
        let c = content.map { ",\"content\":\($0.debugDescription)" } ?? ""
        return "{\"type\":\"queue-operation\",\"operation\":\"\(op)\"\(c)}"
    }
    private func queuedTexts(_ lines: [String]) -> [String] {
        Transcript.parse(lines).compactMap {
            if case let .queued(_, t) = $0 { return t } else { return nil }
        }
    }

    /// ⚠️ `dequeue` **从来不带 content**，只能弹队头。
    /// 漏了这一支，打错的斜杠命令那条气泡**永远挂着**（它既不写 remove，
    /// 也永远不会作为 user 消息出现）。
    func testDequeuePopsHead() {
        XCTAssertEqual(queuedTexts([line("enqueue", "甲"), line("enqueue", "乙"), line("dequeue")]),
                       ["乙"])
    }

    /// ⚠️ `popAll`：TUI 里按 ↑ 把排队的全收回输入框，每收一条一行。
    /// 漏了它，用户撤回之后气泡再也不消失。
    func testPopAllRemoves() {
        XCTAssertEqual(queuedTexts([line("enqueue", "甲"), line("enqueue", "乙"),
                                    line("popAll", "甲"), line("popAll", "乙")]), [])
    }

    /// ⚠️⚠️ **同一句话排两次就是两条。** 去重等于真的丢消息。
    func testDuplicatesAreTwoEntries() {
        XCTAssertEqual(queuedTexts([line("enqueue", "同一句"), line("enqueue", "同一句")]).count, 2)
    }

    /// ⚠️ 空 content 要**占位**（老格式的 enqueue 没有 content），
    /// 不占位的话 dequeue 会弹错人；但**展示时要滤掉**。
    func testEmptyHoldsSlotButIsNotShown() {
        let out = queuedTexts([line("enqueue"), line("enqueue", "真话"), line("dequeue")])
        XCTAssertEqual(out, ["真话"], "空占位没起作用，dequeue 弹错了人：\(out)")
    }

    /// 系统注入的整块 XML 不能顶着「你排队的输入」显示
    func testTaskNotificationNotShown() {
        XCTAssertEqual(queuedTexts([line("enqueue", "<task-notification>内部</task-notification>")]), [])
    }
}

/// ⚡模式里的模型命令。
///
/// ⚠️ **这些是从真转录里核过的形式，不是凭感觉写的。**
/// `/model opus[1m]` 实测**不认**（回「Kept model as …」，等于没切）；
/// 认的是全名带后缀，两条都有成功回执：
///   `claude-opus-4-6[1m]` → 「Set model to Opus 4.6 (1M context)」
///   `claude-opus-5[1m]`   → 「Set model to Opus 5 (1M context)」
final class ModeCommandTests: XCTestCase {

    func testModelCommandsUseFullName() {
        let models = Modes.defaults.filter { $0.command.hasPrefix("/model") }
        XCTAssertFalse(models.isEmpty, "一个切模型的都没有")
        for m in models {
            XCTAssertTrue(m.command.contains("claude-"),
                          "「\(m.command)」不是全名形式 —— 短别名实测切不动")
        }
    }

    /// 1M 上下文那两条必须带 `[1m]` 后缀，漏了就是普通上下文
    func testOneMillionSuffix() {
        for m in Modes.defaults where m.label.contains("1M") {
            XCTAssertTrue(m.command.hasSuffix("[1m]"), "「\(m.label)」漏了 [1m]：\(m.command)")
        }
    }

    /// 标签要认得出是哪个模型 —— 面板上一排 chip，「1M 上下文」看不出是哪一个
    func testLabelsNameTheModel() {
        XCTAssertTrue(Modes.defaults.contains { $0.label.contains("4.6") })
        XCTAssertTrue(Modes.defaults.contains { $0.label.contains("5") })
    }
}
