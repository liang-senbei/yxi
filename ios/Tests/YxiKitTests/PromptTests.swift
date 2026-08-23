import XCTest
@testable import YxiKit

/// TUI 选择器的解析。**样本全部是 `tmux capture-pane -p` 原样抓下来的**。
///
/// 这一块错了不会报错，只会**默默选错** —— 送出去的数字落到另一个选项上。
/// 所以每条测试钉的都是「什么情况下会悄悄选错」。
final class PromptTests: XCTestCase {

    /// 防 TROUBLESHOOTING #46 + #47。
    /// #46：权限提示的脚注是 `Esc to cancel · Tab to amend · ctrl+e to explain`，
    ///      **没有 `to navigate`** —— 只认 navigate 的规则把最该认出来的一种整个漏掉了。
    /// #47：「拒绝」是 **3** 不是 2 —— 2 是「以后都别问」。通知按钮上要是把「拒绝」
    ///      硬编码成 2，用户点一下 = **永久放行这一类命令**，而他以为自己拒绝了。
    ///      所以号码必须从屏幕上读。
    func test_权限提示() {
        guard let p = Prompt.parse(Fixture.permA) else { return XCTFail("权限提示没认出来") }
        XCTAssertEqual(p.title, "Do you want to proceed?")
        XCTAssertFalse(p.multiSelect)
        XCTAssertEqual(p.options.map(\.number), [1, 2, 3])
        XCTAssertEqual(p.options[0].label, "Yes")
        XCTAssertEqual(p.options[2].label, "No")
        XCTAssertTrue(p.options[1].label.hasPrefix("Yes,"), "2 号是「以后都别问」，不是拒绝")
    }

    /// 防 #53：**两个不同的权限提示，标题和三个选项的形状可以完全一样。**
    /// 只比「几号 + 选项文案」等于没比 —— 你在终端里答掉了 A、屏幕换成了 B，
    /// 通知按钮上的那一下会落到 B 上，而且没有任何提示。
    /// 真正能区分的是上面那几行命令正文，所以指纹必须把它们圈进来。
    func test_两个权限提示的指纹必须不同() {
        guard let a = Prompt.parse(Fixture.permA), let b = Prompt.parse(Fixture.permB) else {
            return XCTFail("有一个没认出来")
        }
        // 先确认「只比选项」确实挡不住：标题一样，1 号和 3 号一模一样
        XCTAssertEqual(a.title, b.title)
        XCTAssertEqual(a.options[0].label, b.options[0].label)
        XCTAssertEqual(a.options[2].label, b.options[2].label)
        // 指纹必须不一样
        XCTAssertNotEqual(a.fingerprint, b.fingerprint, "命令不同的两个提示，指纹却相同")
    }

    /// 同一个提示反复解析，指纹要稳定 —— 不稳定的话每次抓屏都判「提示变了」，按钮永远按不动。
    func test_同一个提示指纹稳定() {
        XCTAssertEqual(Prompt.parse(Fixture.permA)?.fingerprint, Prompt.parse(Fixture.permA)?.fingerprint)
        XCTAssertEqual(Prompt.parse(Fixture.askSingle)?.fingerprint, Prompt.parse(Fixture.askSingle)?.fingerprint)
    }

    /// ⚠️ **指纹必须跨进程稳定，光「同一次运行里一样」不够。**
    /// 它会跟着通知一路带到「用户几分钟后点了通知上的按钮」那一刻，
    /// 而那时候 App 很可能已经被系统杀过一轮、重新拉起来了。
    /// Swift 的 `hashValue` 每个进程随机加盐 —— 用它的话重启之后**每一个提示
    /// 都会判成「提示变了」**，按钮永远按不动，而且看起来像随机失灵。
    ///
    /// 这条断言写死一个字面值，就是为了让「换成带盐的哈希」当场变红。
    /// （指纹算法本身要是有意改了，这条会红 —— 那是对的：改算法等于让所有
    /// 已经发出去的通知全部作废，本来就该是一次自觉的改动。）
    func test_指纹跨进程稳定() {
        XCTAssertEqual(Prompt.parse(Fixture.permA)?.fingerprint, "def4c91f8dfb3238")
    }

    func test_单选() {
        guard let p = Prompt.parse(Fixture.askSingle) else { return XCTFail("没认出来") }
        XCTAssertEqual(p.title, "晚饭吃面还是吃饭？")
        XCTAssertFalse(p.multiSelect)
        XCTAssertEqual(p.options.map(\.number), [1, 2, 3, 4])
        XCTAssertEqual(p.options[0].label, "吃面")
        XCTAssertEqual(p.options[0].description, "来一碗面")
        // ⚠️ 脚注那行不能变成最后一项的说明（真机上看见过）
        XCTAssertFalse(p.options.contains { $0.description.contains("to navigate") })
    }

    /// 多选：`[ ]` / `[✔]` 前缀要剥掉，勾选状态要读出来。
    /// ⚠️ 多选时送数字只是**切换勾选**，要 `Right` + `1` 才算交卷（#29）——
    /// `multiSelect` 这个标志就是给送键那层看的，认错了就会「点了没反应」。
    func test_多选带勾选状态() {
        guard let p = Prompt.parse(Fixture.askMulti) else { return XCTFail("没认出来") }
        XCTAssertEqual(p.title, "配菜加哪些？")
        XCTAssertTrue(p.multiSelect)
        XCTAssertEqual(p.options[0].label, "黑椒牛柳", "`[ ] ` 前缀要剥掉")
        XCTAssertFalse(p.options[0].checked)
        XCTAssertTrue(p.options[2].checked, "第 3 项在真机上已经用 send-keys 勾上了")
    }

    /// 计划批准框。这一屏上同时有两个坑：
    ///
    /// ① 防 #30：**计划正文本身就是 `1. 烧水 / 2. 下面 / 3. 出锅`，紧贴在真选项上面。**
    ///    按「脚注上面 N 行里的编号行」收会把它们一起收进来，用户点第 3 项时送出去的
    ///    `3` 会落到真选项的第 3 个上 —— 点 A 选中 B，而且不报错。
    ///    靠「编号必须连续递减到 1」才挡得住。
    ///
    /// ② 新坑（2.1.241 实测）：这一屏的脚注是
    ///    `ctrl+g to edit in VS Code · ~/.claude/plans/….md` ——
    ///    **`to cancel` 和 `to navigate` 一个都没有**。安卓那版的 `isFooter`
    ///    在这一屏上直接返回 null，整个计划批准框认不出来，
    ///    也就是「在手机上批计划」根本没法用。#46 的同一个家族又踩了一次：
    ///    **拿样本推规则时，样本的覆盖面比样本的精确度更要紧。**
    func test_计划批准框() {
        guard let p = Prompt.parse(Fixture.planApproval) else {
            return XCTFail("计划批准框没认出来 —— 多半是脚注又换了一种形态")
        }
        XCTAssertTrue(p.title.contains("Would you like to proceed"), "标题=\(p.title)")
        XCTAssertEqual(p.options.count, 3, "计划正文里的 1./2./3. 被当成选项了：\(p.options)")
        XCTAssertEqual(p.options.map(\.number), [1, 2, 3])
        XCTAssertTrue(p.options[0].label.hasPrefix("Yes,"))
        XCTAssertFalse(p.options.contains { $0.label.contains("烧水") }, "计划正文不是选项")
    }

    /// ⚠️ **正文里随口提一句计划文件，不算「在等你选」。**
    /// 真机上抓到的这一屏：正文是 `1. 烧水 / 2. 下锅 / 3. 出锅`，紧接着一行
    /// 「计划已存到 ~/.claude/plans/lively-honking-widget.md」。那行路径长得跟
    /// 计划批准框的脚注一模一样 —— 认成脚注的话，那三个步骤就变成三个可点的「选项」，
    /// **点一下就往一个根本没在等人选的会话里送按键**。
    ///
    /// 挡住它的是：`.claude/plans/` 这条锚点额外要求「它是屏幕上最后一条非空行」。
    /// 真的选择器一定占着屏幕最底下；随口一提的那行下面还有输入框和模式行。
    func test_正文里提到计划文件不算提示() {
        XCTAssertTrue(Fixture.plansPathMention.contains(".claude/plans/"), "样本得真的含那行路径")
        XCTAssertNil(Prompt.parse(Fixture.plansPathMention))
        // ⚠️ 这一屏才是真正要命的那种：三步是**缩进**的，跟真选项一模一样
        // （上一屏第一步顶着 `● ` 所以碰巧不匹配选项的正则 —— 那是运气，不是规则）。
        XCTAssertTrue(Fixture.plansPathWithList.contains("\n  1. 烧水"), "样本得真的有缩进的编号行")
        XCTAssertNil(Prompt.parse(Fixture.plansPathWithList),
                     "会话根本没在等人选，却给了三个可点的选项")
    }

    /// ⚠️ **用户在输入框里打了一句带编号的话，不算「在等你选」。**
    /// 真机这一屏：输入框里是没发出去的 `1. 先做这个`，渲染成 `❯ 1. 先做这个` ——
    /// 跟选择器的光标行一字不差（连 `❯` 后面那个不换行空格都一样）。
    /// 认成选择器的话，用户每打一句带编号的话就冒一张「等你选」的卡片，
    /// 点一下往 pty 里打个 `1` 进他正在写的句子里。
    ///
    /// 挡住它的是「光标锚的选项块至少两行」：输入框下面紧跟着就是那条横线。
    func test_输入框里打了编号不算提示() {
        XCTAssertTrue(Fixture.typedNumberedInInputBox.contains("❯\u{a0}1. 先做这个"), "样本得真有那行")
        XCTAssertNil(Prompt.parse(Fixture.typedNumberedInInputBox))
    }

    /// 没在等人选的时候必须返回 nil，不能把普通输出当成选项。
    /// ⚠️ 用的是**真机的整屏**（忙的一屏 + 闲的一屏），不是编的 ——
    /// 真屏幕最底下那行模式提示（`⏵⏵ auto mode on (shift+tab to cycle) · esc to interrupt`）
    /// 长得跟脚注很像，这条测试就是在钉它不会被当成脚注。
    func test_普通屏幕不算提示() {
        XCTAssertNil(Prompt.parse(Fixture.idleScreen))
        XCTAssertNil(Prompt.parse(Fixture.busyQueued))
        XCTAssertNil(Prompt.parse(""))
    }
}
