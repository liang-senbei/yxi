import XCTest
@testable import YxiKit

/// 转录解析。样本全部来自真机转录（见 [Fixture] 顶上的说明）。
final class TranscriptTests: XCTestCase {

    /// 防 TROUBLESHOOTING #36：只把斜杠换成横杠不够，中文路径的会话永远「找不到转录」。
    /// ⚠️ 断言里写的是**本机 `~/.claude/projects/` 下真实存在的目录名**，不是推的。
    func test_项目目录名() {
        XCTAssertEqual(Transcript.projectDirOf("/root/src/workspace/Yxi"), "-root-src-workspace-Yxi")
        XCTAssertEqual(Transcript.projectDirOf("/tmp"), "-tmp")
        // 汉字：一个字一个横杠。`/opt/workspace/日常对话` → `-opt-workspace-----`
        XCTAssertEqual(Transcript.projectDirOf("/opt/workspace/日常对话"), "-opt-workspace-----")
        XCTAssertEqual(Transcript.projectDirOf("/opt/workspace/环境"), "-opt-workspace---")
        // 点、空格、横杠本身也都变横杠
        XCTAssertEqual(Transcript.projectDirOf("/root/.claude"), "-root--claude")
        XCTAssertEqual(Transcript.projectDirOf("/a b.c"), "-a-b-c")
        XCTAssertEqual(Transcript.projectDirOf("/x-y"), "-x-y")
        // 真机上确实存在的那个长目录（本次测试数据就是从它里面抠的）
        XCTAssertEqual(
            Transcript.projectDirOf("/tmp/claude-0/-root-src-workspace-Yxi/d0ccc7db-ab52-458f-807f-39247666d0c2/scratchpad/iosparse"),
            "-tmp-claude-0--root-src-workspace-Yxi-d0ccc7db-ab52-458f-807f-39247666d0c2-scratchpad-iosparse"
        )
        // ⚠️ emoji 在 JS（规则那头）眼里是**两个** UTF-16 码元 = 两个横杠。
        // 按 Swift 的 Character 数只会得到一个，于是路径带 emoji 的会话静默「没有转录」。
        XCTAssertEqual(Transcript.projectDirOf("/a/🙂"), "-a---")
    }

    /// 工具结果要合并回**同一张卡片**，不能单独成条。
    /// 富渲染靠的是顶层 `toolUseResult`（这里是 Edit 的 `structuredPatch`），不是 `tool_result` 正文。
    func test_工具结果合并回卡片而不是单独成条() {
        let items = Transcript.parse([Fixture.assistantEdit, Fixture.userEditResult])
        XCTAssertEqual(items.count, 1, "结果不该另起一条：\(items)")
        guard case let .tool(t) = items[0] else { return XCTFail("应该是工具卡片") }
        XCTAssertEqual(t.name, "Edit")
        XCTAssertEqual(t.input["old_string"].string, "print(\"hello\")")
        XCTAssertFalse(t.isError)
        XCTAssertTrue(t.result?.contains("has been updated successfully") == true)
        // diff 视图全靠它
        XCTAssertEqual(t.meta["structuredPatch"][0]["lines"][0].string, "-print(\"hello\")")
        XCTAssertEqual(t.meta["structuredPatch"][0]["lines"][1].string, "+print(\"world\")")
    }

    /// ⚠️ **`toolUseResult` 可能是一个字符串，不是对象。** 真机上这条的它就是
    /// `"Error: Exit code 2\nls: cannot access …"`。当成对象取字段会静默取到空串，
    /// 于是「有输出但卡片是空的」——所以解析器只在它确实是对象时才填 `meta`，
    /// 渲染层看见 `meta == .null` 就该退回用 `result` 正文。
    func test_toolUseResult是字符串时不当对象用() {
        let items = Transcript.parse([Fixture.assistantBashErr, Fixture.userBashErrResult])
        guard case let .tool(t) = items[0] else { return XCTFail("应该是工具卡片") }
        XCTAssertEqual(t.meta, .null, "字符串形态的 toolUseResult 不该被当对象存进 meta")
        XCTAssertTrue(t.isError, "退出码非 0 要能看出来")
        XCTAssertTrue(t.result?.contains("No such file or directory") == true, "正文不能丢")
    }

    /// Bash 的 `toolUseResult` 里 **stdout 和 stderr 是分开的两个字段** ——
    /// 卡片要把 stderr 标红，混在一起就分不出来了。
    func test_Bash的stdout和stderr是分开的() {
        let items = Transcript.parse([Fixture.assistantBashOk, Fixture.userBashOkResult])
        guard case let .tool(t) = items[0] else { return XCTFail("应该是工具卡片") }
        XCTAssertEqual(t.meta["stdout"].string, "world")
        XCTAssertEqual(t.meta["stderr"].string, "")
        XCTAssertFalse(t.meta["interrupted"].bool, "true/false 不能变成 1/0")
    }

    /// AskUserQuestion 的答案在 `toolUseResult.answers` 里 —— 选项卡片渲染靠它。
    func test_AskUserQuestion的答案进meta() {
        let items = Transcript.parse([Fixture.assistantAsk, Fixture.userAskResult])
        guard case let .tool(t) = items[0] else { return XCTFail("应该是工具卡片") }
        XCTAssertEqual(t.name, "AskUserQuestion")
        XCTAssertEqual(t.meta["answers"]["晚饭吃面还是吃饭？"].string, "吃面")
        XCTAssertEqual(t.input["questions"][0]["options"][0]["label"].string, "吃面")
    }

    /// ⚠️ 真机 2.1.241 的 `thinking` 块**正文是空串**（只剩加密的 signature）。
    /// 空正文不能冒一个空的「思考」气泡出来 —— 那比不显示更让人困惑。
    func test_空的thinking不冒空气泡() {
        XCTAssertTrue(Transcript.parse([Fixture.assistantThinkingEmpty]).isEmpty)
    }

    /// 非消息行（`mode` / `file-history-snapshot` / `ai-title` / `system`）安静跳过。
    /// ⚠️ 但**不能顺手把没有 `message` 的行全丢掉** —— 排队的输入也没有 `message`，
    /// 那正是 #72 的翻车方式。所以这条测试的搭档是 `QueuedTests` 里那几条。
    func test_非消息行安静跳过() {
        XCTAssertTrue(Transcript.parse([Fixture.modeLine]).isEmpty)
    }

    /// ⚠️ **侧链（子 agent 的内部独白）不进主时间线**，否则主线会被淹掉。
    /// 2.1.241 起子 agent 另存到 `<会话uuid>/subagents/*.jsonl`（这条样本就是从那儿抠的），
    /// 主文件里已经没有 `isSidechain: true` 了 —— 但**老转录里有**，
    /// 而用户的会话动辄跨好几个版本。
    func test_侧链不进主时间线() {
        XCTAssertTrue(Fixture.sidechainLine.contains("\"isSidechain\":true"), "样本得真是侧链")
        XCTAssertTrue(Transcript.parse([Fixture.sidechainLine]).isEmpty)
        // 混在正常行里也只丢它自己
        XCTAssertEqual(Transcript.parse([Fixture.sidechainLine, Fixture.assistantText]).count, 1)
    }

    /// ⚠️ `tail -f` 追一个正在写的文件时**一定会读到半行**。那不是错误，是常态：
    /// 半行必须只丢它自己，不能把这一批里其它的好行一起带走。
    func test_半行不会带垮整批() {
        let half = String(Fixture.assistantText.prefix(120))     // 真实那行的前半截
        let items = Transcript.parse([half, Fixture.assistantText, ""])
        XCTAssertEqual(items.count, 1, "好行要留下：\(items)")
    }

    /// 同一批行解析两次，id 必须一模一样。
    /// ⚠️ 界面每收到几行就整批重解一次；id 只要变，SwiftUI 就会把整列判成全新的、
    /// 整屏重画并把用户从他正在看的地方拽走。**所以兜底 id 不能用 `hashValue`**（带随机盐）。
    func test_同一批行解析两次id不变() {
        let lines = [Fixture.assistantEdit, Fixture.userEditResult, Fixture.assistantText]
        XCTAssertEqual(Transcript.parse(lines).map(\.id), Transcript.parse(lines).map(\.id))
    }
}
