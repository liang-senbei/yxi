import XCTest
@testable import YxiKit

/// 注入内容 / API 报错。
///
/// ⚠️ **不是边角情况**：本机最近 25 份转录里注入类共 422 处
/// （task-notification 201、teammate-message 85、local-command-stdout 50、
/// agent-message 30、command-name 29、local-command-caveat 21、system-reminder 6），
/// API 报错 5 处。不认的话它们会顶着「你说的话」的气泡显示一坨 XML。
final class InjectedTests: XCTestCase {

    private func items(_ lines: [String]) -> [ChatItem] { Transcript.parse(lines) }

    private func userLine(_ text: String) -> String {
        let msg: [String: Any] = ["role": "user", "content": text]
        let d: [String: Any] = ["type": "user", "uuid": "u1", "message": msg]
        return String(decoding: try! JSONSerialization.data(withJSONObject: d), as: UTF8.self)
    }

    /// 真实形状：正文前面还有一句人话，标签在中间
    func test_队友消息不算用户说的话() {
        let real = "Another Claude session sent a message:\n"
            + "<teammate-message teammate_id=\"term-widget\" color=\"blue\">\n{\"type\":\"idle\"}\n</teammate-message>"
        let out = items([userLine(real)])
        guard case let .injected(_, label, from, _) = out.first else {
            return XCTFail("没认出来，会顶着「你说的话」显示 XML：\(out)")
        }
        XCTAssertEqual(label, "队友消息")
        // ⚠️ 队友消息用的是 `teammate_id=`，只认 `from=` 的话这里永远是 nil
        XCTAssertEqual(from, "term-widget")
    }

    func test_任务通知不算用户说的话() {
        guard case .injected(_, "任务通知", _, _)
            = items([userLine("<task-notification>\n<task-id>abc</task-id>\n</task-notification>")]).first
        else { return XCTFail("任务通知没认出来") }
    }

    func test_斜杠命令和命令输出() {
        guard case .injected(_, "斜杠命令", _, _)
            = items([userLine("<command-name>/goal</command-name>\n<command-message>goal</command-message>")]).first
        else { return XCTFail("斜杠命令没认出来") }
        guard case .injected(_, "命令输出", _, _)
            = items([userLine("<local-command-stdout>Goal set: …</local-command-stdout>")]).first
        else { return XCTFail("命令输出没认出来") }
    }

    /// 真的人打的话必须还是 `.user` —— 别把所有东西都判成注入
    func test_人打的还是用户消息() {
        guard case .user = items([userLine("帮我看看这个 bug")]).first else {
            return XCTFail("把人打的话判成注入了")
        }
    }

    /// 正文里提到标签名（不是真标签）不该中招
    func test_提到名字不算() {
        guard case .user = items([userLine("teammate-message 是什么意思")]).first else {
            return XCTFail("光提到名字就被当成注入了")
        }
    }

    /// 命令输出里常带 ANSI，照原样显示是一串乱码
    func test_洗掉ANSI() {
        let withAnsi = "<local-command-stdout>Set model to \u{1B}[1mOpus 5\u{1B}[22m</local-command-stdout>"
        guard case let .injected(_, _, _, text) = items([userLine(withAnsi)]).first else {
            return XCTFail("没认出来")
        }
        XCTAssertFalse(text.contains("\u{1B}"), "ANSI 没洗干净：\(text.debugDescription)")
        XCTAssertTrue(text.contains("Opus 5"), "把内容也洗掉了：\(text)")
    }

    /// API 报错当正文渲染会让人以为是 Claude 的回答
    func test_API报错单独一类() {
        let d: [String: Any] = [
            "type": "user", "uuid": "e1", "isApiErrorMessage": true,
            "message": ["role": "user", "content": "API Error: Request timed out."],
        ]
        let line = String(decoding: try! JSONSerialization.data(withJSONObject: d), as: UTF8.self)
        guard case let .apiError(_, text) = items([line]).first else {
            return XCTFail("API 报错被当成正文了：\(items([line]))")
        }
        XCTAssertTrue(text.contains("timed out"))
    }
}
