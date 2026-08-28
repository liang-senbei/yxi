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

    /// ⚠️ **屏幕上「提到」状态词的那几行不能当状态行。**
    /// 真机样本里带缩进印着 `  · Scampering… (4m 48s · ↓ 10.2k tokens)` 和
    /// `  ✽ Pondering… (12s)` —— 是我让它把这两行原样回给我，于是它们作为**正文**
    /// 出现在转录区。它们跟真状态行的唯一区别就是**没顶格**。
    ///
    /// 跟下一条一样，**必须先把真状态行去掉**：真状态行永远在最底下，而解析是倒着找第一条，
    /// 留着它这条测试就绿得没有理由 —— 它根本走不到「顶格」那条规则。
    func test_正文里印着状态词不算状态行() {
        let 去掉状态行 = Fixture.busyIndentedStatus
            .components(separatedBy: "\n")
            .filter { !$0.hasPrefix("✻ Reticulating…") }
            .joined(separator: "\n")
        XCTAssertTrue(去掉状态行.contains("  · Scampering… (4m 48s"), "样本得真的还留着那两行缩进的")
        let live = Live.parse(去掉状态行)
        XCTAssertTrue(live.busy)
        XCTAssertNil(live.status, "缩进的那两行被当成状态词了：\(live.status ?? "nil")")
    }

    /// ⚠️ **中文不写空格 —— 于是一条顶格的中文行长得跟状态行一模一样。**
    ///
    /// 真机上抓到的这一行：`❯ 排队丙：这条带省略号…后面还有字`
    /// 顶格、一个符号开头、后面跟着一段**不含空格且带 `…`** 的文字 ——
    /// 「顶格 + `…` 前面不能有空格」这条规则对它完全无效。
    ///
    /// ⚠️ **诚实说明这条防的是什么：**
    /// 这一屏是真的（`tmux capture-pane` 原样），那行中文也是真的顶格渲染在忙的屏幕上。
    /// 但**这一帧本身解出来是对的** —— 真状态行 `· Gusting…` 排在它下面，
    /// 而解析是倒着取第一条，所以撞不上。所以下面**先把真状态行删掉**才验得到这条规则，
    /// 用的是跟上一条同样的手法。
    ///
    /// 我试过造出「中文那行成为最后一条匹配」的完整帧（三轮、约 60 次抓屏）**没造出来**：
    /// 忙的时候 TUI 总把状态行渲染在输出区最底下，而它的首词总以 `…` 结尾。
    /// 唯一不带 `…` 的状态形态是 `✻ Waiting for N background agent to finish`，
    /// 但没能让它跟一条顶格中文 `…` 行同时出现。
    /// **所以这条是纵深防御，不是已复现的线上误读。**
    /// 谁哪天抓到完整的一帧，把样本换进来，并且删掉这段说明。
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

    /// ⚠️ **不忙就不报状态词，哪怕屏幕上有一行完全符合状态行长相的。**
    /// 真机这一屏是**空闲**的，但顶格印着 `● Reticulating… 这是正文不是状态行` ——
    /// 顶格、拉丁词、带 `…`，三条规则全中，可它只是正文。
    /// 报出来的话手机上会一直转着圈说「正在 Reticulating…」，
    /// 而那个会话早就停下来等你了 —— **看起来永远在忙，等于看不出什么时候该你了**。
    func test_空闲时哪怕有像状态行的正文也不报() {
        XCTAssertTrue(Fixture.idleTopLevelEllipsis.contains("\n● Reticulating… "), "样本得真有那行")
        let live = Live.parse(Fixture.idleTopLevelEllipsis)
        XCTAssertFalse(live.busy)
        XCTAssertNil(live.status, "空闲还报状态：\(live.status ?? "nil")")
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

/// 窄屏（手机就是窄屏）上脚注会被截断，`esc to interrupt` 根本没露出来 ——
/// 那时候必须靠**状态行**判在忙，否则「明明在跑，手机上啥都没有，也停不掉」。
final class BusyWithoutFooterTests: XCTestCase {

    private func screen(_ statusLine: String) -> String {
        """
        ● 前面的正文

        \(statusLine)

        ────────────────────────────────
         > 
        ────────────────────────────────
          ⏵⏵ auto mode on (shift+tab to c…
        """     // ⚠️ 脚注**故意截断**：真机窄屏就是这样，没有 esc to interrupt
    }

    func test_没有脚注也能判出在忙() {
        let live = Live.parse(screen("✻ Gusting… (29s · ↓ 208 tokens)"))
        XCTAssertTrue(live.busy, "脚注被截断就判不出在忙 —— 状态条和「停」都不会出现")
        XCTAssertEqual(live.status, "Gusting… (29s · ↓ 208 tokens)")
    }

    /// 收尾那条不是「在忙」，是结果
    func test_跑完的状态行不算在忙() {
        let live = Live.parse(screen("✻ Baked for 13s"))
        XCTAssertFalse(live.busy, "`for` 那种是跑完了")
        XCTAssertNil(live.status)
    }

    /// 带重音的状态词也要认（`Sautéing…`）—— 已实测 Swift 的 Regex 认这个范围
    func test_带重音的状态词() {
        XCTAssertTrue(Live.parse(screen("✻ Sautéing… (3s)")).busy)
    }

    /// 中文不能当状态词（真机上排队输入会顶格印中文）
    func test_中文不算状态词() {
        XCTAssertFalse(Live.parse(screen("❯ 排队丙：这条带省略号…后面还有字")).busy)
    }
}
