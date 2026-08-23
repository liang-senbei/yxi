import XCTest
@testable import YxiKit

/// 「此刻在忙什么」的解析。样本全部是真机整屏抓下来的。
///
/// 状态词只有屏幕有，永远不落转录 —— 而它是「Claude 还活着」的唯一信号。
/// 没有它，一个跑了五分钟的工具调用在手机上看起来就是界面卡死了。
final class LiveTests: XCTestCase {

    func test_忙的时候认出状态词() {
        let live = Live.parse(Fixture.busyQueued)
        XCTAssertTrue(live.busy, "脚注里有 esc to interrupt 就是在忙")
        XCTAssertTrue(live.status?.hasPrefix("Embellishing…") == true, "状态词=\(live.status ?? "nil")")
    }

    /// `✻ Brewed for 34s` 是**收尾**的形态（过去式 + for，没有 `…`），不是「正在忙」。
    func test_空闲时不报状态() {
        let live = Live.parse(Fixture.idleScreen)
        XCTAssertFalse(live.busy)
        XCTAssertNil(live.status)
    }

    /// ⚠️ 这条测试**必须把真状态行去掉才有意义**。
    /// 真状态行永远渲染在输出区最底下，而解析是倒着找第一条 ——
    /// 留着它的话这条测试会「绿得没有理由」：它根本走不到要验的那条规则。
    /// 去掉之后，屏幕上唯一还像状态行的就只剩带缩进的那些，
    /// 于是「必须顶格」成了唯一挡住它们的东西。
    func test_带缩进的不算状态行() {
        let 去掉状态行 = Fixture.busyQueued
            .components(separatedBy: "\n")
            .filter { !$0.hasPrefix("✶ ") && !$0.hasPrefix("✽ ") && !$0.hasPrefix("✢ ") && !$0.hasPrefix("· ") }
            .joined(separator: "\n")
        let live = Live.parse(去掉状态行)
        XCTAssertTrue(live.busy, "这份样本里应该仍然是「在忙」")
        XCTAssertNil(live.status, "缩进的那些不能当状态：\(live.status ?? "nil")")
    }

    /// ⚠️ **中文不写空格 —— 于是一条顶格的中文排队输入长得跟状态行一模一样。**
    ///
    /// 真机上抓到的这一行：`❯ 排队丙：这条带省略号…后面还有字`
    /// 顶格、一个符号开头、后面跟着一段**不含空格且带 `…`** 的文字 ——
    /// 「顶格 + `…` 前面不能有空格」这条规则对它完全无效。
    /// 安卓那版就是这条规则，所以它有这个 bug；手机上会显示成
    /// 「正在 排队丙：这条带省略号…后面还有字」。
    ///
    /// 跟上一条一样，**必须先把真状态行去掉**，否则「倒着取第一条」会先撞上真的那条，
    /// 测试就绿得没有理由了。
    func test_中文里的省略号不能当状态词() {
        let 去掉状态行 = Fixture.busyCJKEllipsis
            .components(separatedBy: "\n")
            .filter { !$0.hasPrefix("· Gusting…") }
            .joined(separator: "\n")
        // 先确认这份样本里确实还留着那条会骗人的中文行，否则测试是空转的
        XCTAssertTrue(去掉状态行.contains("\n❯ 排队丙：这条带省略号…后面还有字"))
        let live = Live.parse(去掉状态行)
        XCTAssertTrue(live.busy)
        XCTAssertNil(live.status, "中文排队输入被当成状态词了：\(live.status ?? "nil")")
    }

    /// 真状态行还在的时候，认的是它，不是上面那条中文。
    func test_真状态行仍然认得出来() {
        XCTAssertEqual(Live.parse(Fixture.busyCJKEllipsis).status, "Gusting… (29s · ↓ 208 tokens)")
    }

    /// 一次抓屏同时解出「等你选」和「在忙什么」。
    /// ⚠️ 合成一次是有意的：分两次抓会**看到两个不同时刻的屏幕**，
    /// 状态和待答对不上，表现成偶发的闪烁，非常难查。
    func test_一次抓屏两件事一起解() {
        let (pending, live) = SessionProbe.readScreen(Fixture.permA)
        XCTAssertNotNil(pending)
        XCTAssertFalse(live.busy)
    }
}
