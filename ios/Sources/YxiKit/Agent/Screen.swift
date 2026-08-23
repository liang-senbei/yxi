import Foundation

// 从 `tmux capture-pane -p` 的屏幕文本里刮两件**转录里没有**的事：
//   · [Prompt] —— 正在等你选的那个选择器
//   · [Live]   —— 此刻在忙什么
//
// ⚠️ **只刮这两件。** 历史和排队的输入都从转录读（结构化、权威）。
// 一开始排队的输入也是刮屏拿的，能用，但要处理折行拼接、还要跟转录比对去重
// 才能分清「排队中」和「已处理」—— 而转录里本来就有原文和进出队时刻。
// 教训：**能从权威来源拿的就别刮屏**（TROUBLESHOOTING #72 的弯路那段）。

/// 认出**正在等你回答的那个选择器**。
///
/// ⚠️ **为什么不从转录里读**：实测过了 —— Claude Code 的 `tool_use` 块
/// **要等工具跑完才写进 JSONL**。「问题挂在那儿等你」的那段时间里转录中什么都没有
/// （用户消息在、assistant 那条不在）。见 TROUBLESHOOTING #28。
///
/// ⚠️ **顺带的好处**：屏幕上写着几号，我们就送几号。
/// 要是改成「从 JSON 读选项、按下标送键」，一旦顺序对不上就会**点 A 选中 B** ——
/// 那种错误不会报错，只会默默选错。**屏幕上的数字就是契约。**
///
/// 按键协议全部实测过（送给 `tmux send-keys`，TROUBLESHOOTING #29）：
///   · 单选：送数字 → **直接选中并确认**，不用再送 Enter
///   · 多选：送数字 → 切换勾选；`Right` → 跳到 Submit 页；再送 `1` → 提交
///   · 多个问题：`Left` / `Right` 在问题标签之间切换
///   · 通用：`Up`/`Down`/`j`/`k` 移动，`Esc` 取消
public enum Prompt {

    private static let option = try! Regex(#"\s*[❯>]?\s*(\d+)\.\s+(.*\S)\s*"#)
    private static let checkbox = try! Regex(#"\[([ xX✔✓])\]\s*(.*)"#)

    /// 底部这行是选择器的标志。没有它就说明当前没在等人选。
    ///
    /// ⚠️ **脚注不止一种，而且会随版本增加。** 真机上抓到过三种：
    ///   1. `Enter to select · ↑/↓ to navigate · Esc to cancel`（AskUserQuestion）
    ///   2. `Esc to cancel · Tab to amend · ctrl+e to explain`（**权限提示**，没有 navigate）
    ///   3. `ctrl+g to edit in VS Code · ~/.claude/plans/lively-honking-widget.md`
    ///      （**计划批准框**，2.1.241 实测 —— `to cancel` 和 `to navigate` 一个都没有）
    ///
    /// 只认 1 的话权限提示整个认不出来（#46）；只认 1+2 的话**计划批准框**整个认不出来
    /// —— 而「在手机上批计划」正是 Chat View 的招牌功能之一。
    ///
    /// ⚠️ 为什么不用「有 `<某个键> to <某个动作>` 就算脚注」这种通用规则：
    /// 屏幕最底下那行模式提示（`⏵⏵ auto mode on (shift+tab to cycle) · esc to interrupt`）
    /// 也长这样，于是**每一屏都会被判成有选择器**。判错的代价是往别人的服务器上
    /// 送一个按键，所以这里宁可用白名单，加一种就补一条，也不放宽成启发式。
    private static func isFooter(_ l: String) -> Bool {
        if l.contains("to cancel") { return true }
        if l.contains("to navigate") && (l.contains("Enter to") || l.contains("to select")) { return true }
        // 计划批准框：`ctrl+g to edit in <IDE> · ~/.claude/plans/<slug>.md`
        if l.contains(".claude/plans/") || l.contains("ctrl+g to edit") { return true }
        return false
    }

    /// - Parameter screen: `tmux capture-pane -p` 的原样输出
    /// - Returns: 没有在等人选就返回 nil
    public static func parse(_ screen: String) -> Pending? {
        let lines = screen.components(separatedBy: "\n").map { $0.hasSuffix("\r") ? String($0.dropLast()) : $0 }
        guard let footer = lines.lastIndex(where: isFooter) else { return nil }

        // ⚠️ **从脚注往上收，编号必须连续递减到 1。**
        // 不能只按「脚注上面 N 行里的编号行」算 —— 计划正文本身就常常是
        // `1. 烧水 / 2. 下面 / 3. 出锅` 这种编号列表，**就贴在选择器上面**。
        // 把它当成选项，用户点第 3 项时送出去的 `3` 会落到真选项的第 3 个上，
        // **点 A 选中 B，而且不报错**。连续性这条规则才挡得住（TROUBLESHOOTING #30）。
        var numbered: [Int: Int] = [:]          // 选项号 → 行号
        var expected = -1
        var i = footer - 1
        while i >= 0 {
            defer { i -= 1 }
            guard let m = matchOption(lines[i]) else {
                if expected == 0 { break }      // 已经收到 1 号，上面的不要了
                continue
            }
            if expected == -1 || m.number == expected {
                numbered[m.number] = i
                expected = m.number - 1
            } else {
                break                            // 编号断了 —— 上面那些不是这一组的
            }
            if expected == 0 { break }
        }
        guard let firstLine = numbered[1] else { return nil }

        // 正向再走一遍，把选项之间的说明行挂到上一个选项上
        var opts: [Pending.Option] = []
        var multi = false
        // 不含脚注那行本身 —— 含了它会被当成最后一项的「说明」（真机上看见过）
        for j in firstLine..<footer {
            if let m = matchOption(lines[j]), numbered[m.number] == j {
                var label = m.label
                var checked = false
                if let c = matchCheckbox(label) {
                    multi = true
                    checked = c.mark != " "
                    label = c.rest.trimmingCharacters(in: .whitespaces)
                }
                opts.append(Pending.Option(number: m.number, label: label, checked: checked))
            } else if let last = opts.last, !isNoise(lines[j]) {
                if last.description.isEmpty {
                    opts[opts.count - 1] = Pending.Option(
                        number: last.number, label: last.label,
                        description: lines[j].trimmingCharacters(in: .whitespaces),
                        checked: last.checked
                    )
                }
            }
        }

        // 标题 = 1 号选项上面最后一行「像话」的文本
        let title = (0..<firstLine).reversed()
            .first { !isNoise(lines[$0]) && matchOption(lines[$0]) == nil }
            .map { lines[$0].trimmingCharacters(in: .whitespaces) } ?? ""

        // 指纹：从 1 号选项**往上 8 行**一直到脚注，去掉空白后哈希。
        // 往上 8 行是为了把权限提示里的命令正文圈进来 —— **那才是区分两个提示的东西**
        // （两个权限提示的标题和三个选项可以完全一样，TROUBLESHOOTING #53）。
        let from = max(firstLine - 8, 0)
        let fp = stableHash(
            lines[from..<footer]
                .map { $0.trimmingCharacters(in: .whitespaces) }
                .joined(separator: "\n")
                .filter { !$0.isWhitespace }
        )
        return Pending(title: title, options: opts, multiSelect: multi, fingerprint: fp)
    }

    private static func matchOption(_ l: String) -> (number: Int, label: String)? {
        guard let m = l.wholeMatch(of: option),
              let n = m[1].substring.flatMap({ Int($0) }),
              let label = m[2].substring
        else { return nil }
        return (n, String(label))
    }

    private static func matchCheckbox(_ l: String) -> (mark: Character, rest: String)? {
        guard let m = l.wholeMatch(of: checkbox),
              let mark = m[1].substring?.first,
              let rest = m[2].substring
        else { return nil }
        return (mark, String(rest))
    }

    /// 分隔线、标签栏（`←  ☒ 配菜  ✔ Submit  →`）、提示脚注这些不是内容。
    private static func isNoise(_ l: String) -> Bool {
        let t = l.trimmingCharacters(in: .whitespaces)
        if t.isEmpty { return true }
        if t.allSatisfy({ $0 == "─" || $0 == "-" || $0 == "━" || $0 == "╌" }) { return true }
        if t.hasPrefix("←") || t.hasSuffix("→") { return true }
        if t.hasPrefix("shift+tab") || t.hasPrefix("ctrl+") { return true }
        if t.hasPrefix("❯ ") { return true }      // 用户自己刚敲的那行命令
        return false
    }
}

extension Live {

    /// 输入框那两条横线。整行几乎全是 `─` 才算。
    private static func isDivider(_ l: String) -> Bool {
        let t = l.trimmingCharacters(in: .whitespaces)
        return t.count >= 8 && t.allSatisfy { $0 == "─" }
    }

    /// 状态行：**行首一个符号 + 一个以 `…` 结尾的词**，如 `✽ Scampering…`。
    /// 收尾的形态是 `✻ Baked for 13s`（过去式 + for，没有 `…`），那表示已经不忙了。
    ///
    /// ⚠️ 必须要求**顶格**。屏幕上完全可能有别的东西**提到**这些字样 ——
    /// 比如把画面贴进对话里给人看，那几行就带着缩进出现在转录区。
    /// 顶格是 TUI 渲染状态行的唯一形态，拿它当锚点。
    private static let statusLine = try! Regex(#"(\S) (\S*….*)"#)

    /// 状态词还必须是个**拉丁字母词**（`Scampering…` / `Pondering…` / `Sautéing…`）。
    ///
    /// ⚠️ 光有「顶格 + `…` 前面不能有空格」挡不住中文。**中文不写空格**，
    /// 所以顶格的排队输入 `❯ 排队丙：这条带省略号…后面还有字` 会被整条当成状态词 ——
    /// 手机上就显示成「正在 排队丙：这条带省略号…后面还有字」。真机抓屏里确实有这种行
    /// （见 `LiveTests.中文里的省略号不能当状态词`）。**安卓那版就有这个 bug。**
    ///
    /// 范围放到 U+0250 以下是为了保住带重音的词（`Sautéing…` 里的 `é` 是 U+00E9），
    /// 汉字从 U+4E00 起，稳稳挡在外面。
    private static func statusWord(_ captured: String) -> String? {
        let head = captured.prefix { $0 != "…" }
        guard !head.isEmpty,
              head.unicodeScalars.allSatisfy({ $0.value < 0x0250 && Character($0).isLetter })
        else { return nil }
        return captured.trimmingCharacters(in: .whitespaces)
    }

    /// - Parameter screen: `tmux capture-pane -p` 的原样输出
    public static func parse(_ screen: String) -> Live {
        let lines = screen.components(separatedBy: "\n").map {
            String($0.reversed().drop(while: { $0 == " " || $0 == "\t" || $0 == "\r" }).reversed())
        }
        guard !lines.isEmpty else { return .idle }

        // 输入框 = 最后两条横线之间。它下面是脚注，上面是转录区。
        let dividers = lines.indices.filter { isDivider(lines[$0]) }
        let boxTop = dividers.count >= 2 ? dividers[dividers.count - 2] : -1
        let boxBottom = dividers.last ?? lines.count

        let below = boxBottom + 1 < lines.count ? lines[(boxBottom + 1)...].joined(separator: "\n") : ""
        let busy = below.contains("esc to interrupt")
        let above = boxTop >= 0 ? Array(lines[..<boxTop]) : lines

        // 取**最后一条**：屏幕上留着历次的 `✻ Baked for 13s`，只有最后那条是此刻的
        let status = above.reversed().lazy
            .compactMap { l -> String? in
                guard let m = l.wholeMatch(of: statusLine), let s = m[2].substring else { return nil }
                return statusWord(String(s))
            }
            .first

        return Live(busy: busy, status: busy ? status : nil)
    }
}
