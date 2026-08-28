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
