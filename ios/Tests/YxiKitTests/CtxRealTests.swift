import XCTest
@testable import YxiKit

/// 顶栏「模型 · 模式 · 上下文」的解析。
///
/// ⚠️ **样本全部抄自本机 `~/.claude/projects/` 里的真转录**，不是编的 ——
/// 安卓侧那批测试也是这么来的，两端对同一份证据负责。
final class CtxRealTests: XCTestCase {

    /// 真样本：effort=max、model=claude-opus-5、三项 token 分别是 2 / 10594 / 22349
    private let assistantLine = """
    {"type":"assistant","effort":"max","message":{"role":"assistant","model":"claude-opus-5",\
    "usage":{"input_tokens":2,"cache_creation_input_tokens":10594,"cache_read_input_tokens":22349}}}
    """

    func testTokensSumAllThree() {
        let c = Transcript.context([assistantLine])
        XCTAssertNotNil(c)
        // 2 + 10594 + 22349 —— 只看 input_tokens 的话这里会是 2，顶栏就成了「2」
        XCTAssertEqual(c?.tokens, 32945)
        XCTAssertEqual(c?.model, "claude-opus-5")
    }

    /// effort 在**转录行顶层**，不在 message 里 —— 找错地方永远是空
    func testEffortComesFromTopLevel() {
        XCTAssertEqual(Transcript.context([assistantLine])?.effort, "max")
    }

    /// 真样本，一字未改（含 ANSI 的 \u{1B}[1m / [22m 和闭合标签）
    func testSetModelFromRealReceipt() {
        let line = """
        {"isSidechain":false,"type":"user","message":{"role":"user","content":\
        "<local-command-stdout>Set model to \\u001b[1mOpus 4.6 (1M context)\\u001b[22m \
        and saved as your default for new sessions</local-command-stdout>"}}
        """
        // 切了模型但它还没回话 —— 顶栏也必须跟着变
        let c = Transcript.context([assistantLine, line])
        XCTAssertEqual(c?.model, "Opus 4.6 (1M context)",
                       "ANSI 残留和 </local-command-stdout> 都不能被吃进模型名")
    }

    /// 真样本：破折号是 em dash
    func testPonytailLevel() {
        let line = #"{"type":"user","message":{"role":"user","content":"PONYTAIL MODE ACTIVE — level: full\n\n# Ponytail"}}"#
        XCTAssertEqual(Transcript.context([assistantLine, line])?.ponytail, "full")
    }

    /// 真样本的 mode 行
    func testModeLine() {
        let mode = #"{"type":"mode","mode":"plan","sessionId":"bf301a40"}"#
        XCTAssertEqual(Transcript.context([mode, assistantLine])?.mode, "plan")
    }

    /// 子 agent 的内部对话不能顶掉主线的模型名
    func testSidechainIgnored() {
        let side = """
        {"type":"assistant","isSidechain":true,"message":{"model":"claude-haiku-4-5",\
        "usage":{"input_tokens":5,"cache_read_input_tokens":900}}}
        """
        XCTAssertEqual(Transcript.context([assistantLine, side])?.model, "claude-opus-5")
    }

    /// 全是 0 的 usage 不该冒出来一个「0 tokens」——宁可不显示
    func testZeroUsageYieldsNothing() {
        let z = #"{"type":"assistant","message":{"model":"x","usage":{"input_tokens":0}}}"#
        XCTAssertNil(Transcript.context([z]))
    }

    func testEmptyInputIsNil() {
        XCTAssertNil(Transcript.context([]))
        XCTAssertNil(Transcript.context(["", "   ", "不是 json"]))
    }
}
