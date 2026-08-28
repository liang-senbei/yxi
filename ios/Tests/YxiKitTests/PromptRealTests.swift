import XCTest
@testable import YxiKit

/// 用**安卓侧真机抓下来的原始屏幕**盯住 iOS 的选择器解析 —— 两端用同一批样本，
/// 保证「像素级复刻」不是嘴上说说。样本 2026-08-28 抓自真实 Claude Code
/// （AskUserQuestion：两个问题、第二个多选），窄屏那份是把窗口缩到 46 列复现手机情形。
/// 对应安卓侧 `PromptRealTest` 与 TROUBLESHOOTING #133。
final class PromptRealTests: XCTestCase {

    private let single = [
        "───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────",
        "←  ☐ 名字  ☐ 配色  ✔ Submit  →",
        "",
        "给这个 App 起什么名字？",
        "",
        "❯ 1. AskTest",
        "     直接用项目目录名，简单明了",
        "  2. QuickAsk",
        "     突出快速提问的核心功能",
        "  3. AskFlow",
        "     强调提问的流畅体验",
        "  4. AskHub",
        "     定位为提问的中心枢纽",
        "  5. Type something.",
        "───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────",
        "  6. Chat about this",
        "",
        "Enter to select · Tab/Arrow keys to navigate · Esc to cancel",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
    ].joined(separator: "\n")

    private let multi = [
        "───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────",
        "←  ☐ 名字  ☒ 配色  ✔ Submit  →",
        "",
        "主色调用哪个？",
        "",
        "❯ 1. [ ] 蓝色",
        "  专业、信任感，适合工具类产品",
        "  2. [✔] 绿色",
        "  清新、自然，适合轻量交互",
        "  3. [ ] 紫色",
        "  创意、高端，适合 AI 相关产品",
        "  4. [ ] Type something",
        "     Submit",
        "───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────",
        "  5. Chat about this",
        "",
        "Enter to select · Tab/Arrow keys to navigate · Esc to cancel",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
    ].joined(separator: "\n")

    /// 窄屏：脚注会折成两行，安卓侧用户报的 bug 就发生在这个宽度。
    private let narrow = [
        "──────────────────────────────────────────────",
        "←  ☐ 名字  ☒ 配色  ✔ Submit  →",
        "",
        "给这个 App 起什么名字？",
        "",
        "❯ 1. AskTest",
        "     直接用项目目录名，简单明了",
        "  2. QuickAsk",
        "     突出快速提问的核心功能",
        "  3. AskFlow",
        "     强调提问的流畅体验",
        "  4. AskHub",
        "     定位为提问的中心枢纽",
        "  5. Type something.",
        "──────────────────────────────────────────────",
        "  6. Chat about this",
        "",
        "Enter to select · Tab/Arrow keys to navigate ·",
        "Esc to cancel",
        "",
    ].joined(separator: "\n")

    private let review = [
        "──────────────────────────────────────────────",
        "←  ☐ 名字  ☒ 配色  ✔ Submit  →",
        "",
        "Review your answers",
        "",
        "⚠ You have not answered all questions",
        "",
        " ● 主色调用哪个？",
        "   → 绿色",
        "",
        "Ready to submit your answers?",
        "",
        "❯ 1. Submit answers",
        "  2. Cancel",
        "",
        "",
        "",
        "",
    ].joined(separator: "\n")

    func test_单选_问题正文不能丢() throws {
        let p = try XCTUnwrap(Prompt.parse(single))
        XCTAssertEqual(p.title, "给这个 App 起什么名字？")
    }

    func test_单选_选项和说明都对() throws {
        let p = try XCTUnwrap(Prompt.parse(single))
        XCTAssertEqual(p.options.count, 6)
        XCTAssertEqual(p.options[0].label, "AskTest")
        XCTAssertEqual(p.options[0].description, "直接用项目目录名，简单明了")
        XCTAssertEqual(p.options[5].label, "Chat about this")
        // ⚠️ 底部操作提示不能变成最后一项的说明
        XCTAssertFalse(p.options[5].description.contains("Enter to select"))
    }

    func test_多选_认得出勾选状态() throws {
        let p = try XCTUnwrap(Prompt.parse(multi))
        XCTAssertEqual(p.title, "主色调用哪个？")
        XCTAssertTrue(p.multiSelect)
        XCTAssertEqual(p.options[0].label, "蓝色")
        XCTAssertFalse(p.options[0].checked)
        XCTAssertEqual(p.options[1].label, "绿色")
        XCTAssertTrue(p.options[1].checked)      // 屏幕上是 [✔]
    }

    func test_窄屏_脚注换行不能变成最后一项的说明() throws {
        let p = try XCTUnwrap(Prompt.parse(narrow))
        let last = try XCTUnwrap(p.options.last)
        XCTAssertEqual(last.label, "Chat about this")
        XCTAssertEqual(last.description, "")
    }

    func test_窄屏_问题正文照样在() throws {
        let p = try XCTUnwrap(Prompt.parse(narrow))
        XCTAssertEqual(p.title, "给这个 App 起什么名字？")
    }

    func test_认得出多问题的标签栏() throws {
        let p = try XCTUnwrap(Prompt.parse(narrow))
        XCTAssertEqual(p.tabs.count, 3)
        XCTAssertEqual(p.tabs[0].label, "名字")
        XCTAssertFalse(p.tabs[0].answered)
        XCTAssertEqual(p.tabs[1].label, "配色")
        XCTAssertTrue(p.tabs[1].answered)
        XCTAssertTrue(p.tabs[2].submit)
    }

    func test_认得出复核页() throws {
        let p = try XCTUnwrap(Prompt.parse(review))
        XCTAssertTrue(p.review)
        XCTAssertEqual(p.options[0].label, "Submit answers")
    }
}
