import SwiftUI
import MarkdownUI
import YxiKit

/// 工具卡片 —— **按工具定制**（PRD 附录 D.3）。
///
/// 排序理由是实测的调用次数（扫了 145 个转录、294MB）：
/// Bash 5536 · Edit 935 · WebFetch 890 · Read 679 · WebSearch 433 · Write 254 · Agent 148。
/// 所以 Bash 和 Edit 值得单独做，剩下的共用一个朴素卡片就够。
///
/// ⚠️ **富渲染靠 `toolUseResult`（[ToolCall.meta]），不是靠 `tool_result` 的正文。**
/// 正文是给模型看的扁文本（stdout/stderr 混在一起、Read 带 `cat -n` 行号）；
/// meta 才有结构（`structuredPatch`、分开的 stdout/stderr、`answers`）。
/// 但 meta **可能没有**（老转录 / 子 agent 转录里很常见），所以每条路都要有降级。
struct ToolCardView: View {
    let call: ToolCall
    @State private var open: Bool

    init(call: ToolCall) {
        self.call = call
        // ⚠️ **默认折叠成一行。** 一个回合里 Bash/Read 动辄十几条，全铺开的话
        // 正文（Claude 到底说了什么）被挤得几乎看不见 —— 用户原话：
        // 「全都是 bash 和 read 这些可读性太差」。
        //
        // 两个例外**不折叠**，它们不是噪音：
        //   · 出错的 —— 失败才是你要看的那条
        //   · 要你拿主意的（AskUserQuestion / ExitPlanMode）
        _open = State(initialValue: Self.alwaysOpen(call.name) || call.isError)
    }

    static func alwaysOpen(_ name: String) -> Bool {
        name == "AskUserQuestion" || name == "ExitPlanMode"
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header
            if open { detail(for: call.name) }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 16)
        // 折叠时收紧留白 —— 十几张卡片各多 4pt，加起来就是一屏
        .padding(.vertical, open ? 13 : 9)
        .background(Yx.low, in: RoundedRectangle(cornerRadius: Yx.cardRadius, style: .continuous))
        .contentShape(Rectangle())
        .onTapGesture { withAnimation(.easeInOut(duration: 0.18)) { open.toggle() } }
    }

    private var header: some View {
        HStack(spacing: 10) {
            Text(call.name).font(.system(size: 13, weight: .medium)).foregroundStyle(accent)
            if open {
                Spacer(minLength: 0)
            } else {
                Text(summary)
                    .font(.mono(12))
                    .foregroundStyle(Yx.dim)
                    .lineLimit(1).truncationMode(.tail)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            state
        }
    }

    @ViewBuilder private var state: some View {
        // ⚠️ 退出码只在**失败**的输出开头有 `Exit code N`；成功时压根没这个字段，
        // 隐含是 0。所以别指望能显示「exit 0」—— 那是编出来的。
        if let code = exitCode(call.result) {
            Chip(text: "exit \(code)", color: Yx.error)
        } else if call.isError {
            Chip(text: "出错", color: Yx.error)
        } else if call.result != nil {
            Text("完成").font(.mono(12)).foregroundStyle(Yx.dim)
        } else {
            Chip(text: "进行中", color: Yx.amber)
        }
    }

    /// 折叠时那一行摘要 —— **必须能认出「这是哪一条」**，否则折叠就等于全删了。
    /// 命令取第一行、文件取文件名（全路径在手机上一行也放不下）。
    private var summary: String {
        let i = call.input
        let raw: String
        if i["command"].exists          { raw = i["command"].string }
        else if i["file_path"].exists   { raw = base(i["file_path"].string) }
        else if i["pattern"].exists     { raw = i["pattern"].string }
        else if i["description"].exists { raw = i["description"].string }
        else if i["prompt"].exists      { raw = i["prompt"].string }
        else if i["path"].exists        { raw = base(i["path"].string) }
        else if i["url"].exists         { raw = i["url"].string }
        else { raw = i.keys.first.map { i[$0].string } ?? "" }
        return raw.split(separator: "\n", omittingEmptySubsequences: false)
            .lazy.map { $0.trimmingCharacters(in: .whitespaces) }
            .first { !$0.isEmpty } ?? ""
    }

    private var accent: Color {
        switch call.name {
        case "Bash": return Yx.copper
        case "Edit", "Write": return Yx.teal
        // 这两个本来就是「要你拿主意」的，用琥珀
        case "AskUserQuestion", "ExitPlanMode": return Yx.amber
        default: return Yx.muted
        }
    }

    @ViewBuilder
    private func detail(for name: String) -> some View {
        switch name {
        case "Bash": BashBody(call: call)
        case "Edit": EditBody(call: call)
        case "Write": WriteBody(call: call)
        case "Read": ReadBody(call: call)
        case "Agent", "Task": AgentBody(call: call)
        case "AskUserQuestion": AskBody(call: call)
        case "ExitPlanMode": PlanBody(call: call)
        default: PlainBody(call: call)
        }
    }
}

// MARK: - 各工具的正文

private struct BashBody: View {
    let call: ToolCall
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // 命令不折行、横着滚 —— 折行会把长管道拆得没法读
            ScrollView(.horizontal, showsIndicators: false) {
                Text(call.input["command"].string).font(.mono(13)).foregroundStyle(Yx.onSurface)
            }
            .padding(.top, 8)

            let out = call.meta["stdout"].string.isEmpty ? (call.result ?? "") : call.meta["stdout"].string
            let err = call.meta["stderr"].string
            if !out.isEmpty || !err.isEmpty {
                Block {
                    if !out.isEmpty { Code(String(out.prefix(4000)), Yx.muted) }
                    if !err.isEmpty {
                        if !out.isEmpty { Spacer().frame(height: 8) }
                        Code(String(err.prefix(2000)), Yx.delFg)
                    }
                }
            }
        }
    }
}

private struct EditBody: View {
    let call: ToolCall
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            PathRow(call.input["file_path"].string)
            let lines = hunkLines(call.meta["structuredPatch"])
            if !lines.isEmpty {
                DiffBlock(lines: lines)
            } else {
                // meta 没有（老转录）→ 退回原始的 old/new 两段，照样能看出改了什么
                Block {
                    Code("- " + String(call.input["old_string"].string.prefix(1200)), Yx.delFg)
                    Spacer().frame(height: 8)
                    Code("+ " + String(call.input["new_string"].string.prefix(1200)), Yx.addFg)
                }
            }
        }
    }
}

/// `structuredPatch[].lines` 是带 ` `/`-`/`+` 前缀的 unified-diff 行，直接用。
private func hunkLines(_ patch: JSON) -> [String] {
    patch.array.flatMap { $0["lines"].array.map(\.string) }
}

private struct DiffBlock: View {
    let lines: [String]
    var body: some View {
        Block {
            ForEach(Array(lines.enumerated()), id: \.offset) { pair in
                let l = pair.element
                let add = l.first == "+"
                let del = l.first == "-"
                Text(l.isEmpty ? " " : l)
                    .font(.mono(13))
                    .foregroundStyle(add ? Yx.addFg : (del ? Yx.delFg : Yx.dim))
                    .lineLimit(1)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 4)
                    .background(add ? Yx.addBg : (del ? Yx.delBg : .clear))
            }
        }
    }
}

private struct WriteBody: View {
    let call: ToolCall
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            PathRow(call.input["file_path"].string)
            if call.meta["type"].string == "update" {
                Text("覆盖已有文件").font(.system(size: 11.5)).foregroundStyle(Yx.amber).padding(.top, 4)
            }
            Block { Code(String(call.input["content"].string.prefix(3000)), Yx.muted) }
        }
    }
}

private struct ReadBody: View {
    let call: ToolCall
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            PathRow(call.input["file_path"].string)
            let f = call.meta["file"]
            let note: String? = {
                if call.meta["type"].string == "image" {
                    let d = f["dimensions"]
                    return "图片 \(d["originalWidth"].int)×\(d["originalHeight"].int)"
                }
                if f.exists { return "\(f["numLines"].int) 行 / 共 \(f["totalLines"].int) 行" }
                return nil
            }()
            if let note {
                Text(note).font(.mono(12)).foregroundStyle(Yx.dim).padding(.top, 4)
            }
        }
    }
}

private struct AgentBody: View {
    let call: ToolCall
    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(call.input["description"].string)
                .font(.system(size: 14)).foregroundStyle(Yx.onSurfaceVar).padding(.top, 6)
            let async = call.meta["isAsync"].bool
            let tag = [call.input["subagent_type"].string, async ? "后台跑" : ""]
                .filter { !$0.isEmpty }.joined(separator: " · ")
            if !tag.isEmpty { Text(tag).font(.mono(12)).foregroundStyle(Yx.dim) }
            // 异步启动时 result 只是句「已启动」的元数据，展开也没内容可看，别浪费一屏
            if !async, let r = call.result {
                Block { Code(String(r.prefix(4000)), Yx.muted) }
            }
        }
    }
}

/// 已答的问题：显示问了什么、你选了什么。
///
/// ⚠️ **待答的问题不在转录里**（`tool_use` 要等工具跑完才落盘），
/// 那种走 [PendingCard]，数据来自屏幕。
private struct AskBody: View {
    let call: ToolCall
    var body: some View {
        // ⚠️ `answers` 的键是**问题原文**，不是下标；多选的答案是 `", "` 拼起来的**一个字符串**
        let answers = call.meta["answers"]
        VStack(alignment: .leading, spacing: 6) {
            ForEach(Array(call.input["questions"].array.enumerated()), id: \.offset) { pair in
                let text = pair.element["question"].string
                Text(text).font(.system(size: 14)).foregroundStyle(Yx.onSurface).padding(.top, 8)
                let a = answers[text].string
                if !a.isEmpty {
                    Text(a)
                        .font(.system(size: 12.5)).foregroundStyle(Yx.onCopperBox)
                        .padding(.horizontal, 12).padding(.vertical, 5)
                        .background(Yx.copperBox, in: Capsule())
                } else if call.isError {
                    Text("你拒绝了").font(.system(size: 11.5)).foregroundStyle(Yx.dim)
                }
            }
        }
    }
}

private struct PlanBody: View {
    let call: ToolCall
    var body: some View {
        // ⚠️ 用户可能在批准前**改过计划**，所以最终版在 meta 里不在 input 里
        let plan = call.meta["plan"].string.isEmpty ? call.input["plan"].string : call.meta["plan"].string
        VStack(alignment: .leading, spacing: 0) {
            Text(call.isError ? "计划被否了" : "计划")
                .font(.system(size: 12.5)).foregroundStyle(Yx.amber).padding(.top, 6)
            Block {
                Markdown(plan).markdownTheme(.yxi).frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }
}

private struct PlainBody: View {
    let call: ToolCall
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if let s = summarize(call) {
                Text(s).font(.mono(14)).foregroundStyle(Yx.onSurfaceVar)
                    .lineLimit(40).padding(.top, 7)
            }
            if let r = call.result {
                Block { Code(String(r.prefix(4000)), Yx.muted) }
            }
        }
    }
}

private func summarize(_ c: ToolCall) -> String? {
    let s: String
    switch c.name {
    case "WebFetch": s = c.input["url"].string
    case "WebSearch": s = c.input["query"].string
    case "Skill": s = c.input["skill"].string
    default:
        s = String(c.input.keys.map { "\($0): \(c.input[$0].string)" }
            .joined(separator: "\n").prefix(200))
    }
    return s.isEmpty ? nil : s
}

// MARK: - 待答的提示：唯一能点的卡片

/// **此刻正在等你**的那个选择器，数据来自 `tmux capture-pane`。
///
/// 琥珀色 —— 全 app 只有「需要你动手」才用这个颜色（PRD 附录 J.1）。
///
/// ⚠️ **卡片上显示几号，点下去就送几号。** 不要改成按下标送键：
/// 一旦屏幕顺序和列表顺序对不上，就会**点 A 选中 B 且不报错**。
struct PendingCard: View {
    let pending: Pending
    let busy: Bool
    let onPick: (Pending.Option) -> Void
    let onSubmit: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            HStack(spacing: 8) {
                Circle().fill(Yx.amber).frame(width: 7, height: 7)
                Text(pending.multiSelect ? "等你选（可多选）" : "等你选")
                    .font(.system(size: 12.5)).foregroundStyle(Yx.amber)
            }
            if !pending.title.isEmpty {
                Text(pending.title).font(.system(size: 15, weight: .medium)).foregroundStyle(Yx.onSurface)
            }
            ForEach(pending.options) { o in
                Button { onPick(o) } label: {
                    HStack(alignment: .top, spacing: 10) {
                        Text(pending.multiSelect ? (o.checked ? "☑" : "☐") : "\(o.number)")
                            .font(.mono(13, .medium))
                            .foregroundStyle(o.checked ? Yx.onCopperBox : Yx.dim)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(o.label).font(.system(size: 14))
                                .foregroundStyle(o.checked ? Yx.onCopperBox : Yx.onSurface)
                            if !o.description.isEmpty {
                                Text(o.description).font(.system(size: 11.5))
                                    .foregroundStyle(o.checked ? Yx.onCopperBox.opacity(0.7) : Yx.dim)
                            }
                        }
                        Spacer(minLength: 0)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 14).padding(.vertical, 11)
                    .background(o.checked ? Yx.copperBox : Yx.container,
                                in: RoundedRectangle(cornerRadius: Yx.blockRadius, style: .continuous))
                }
                .buttonStyle(.plain)
                .disabled(busy)
            }
            if pending.multiSelect {
                // 多选时数字只是勾选，要 Right + 1 才算交卷（实测，TROUBLESHOOTING #29）
                Button(action: onSubmit) {
                    Text("提交").font(.system(size: 15, weight: .medium))
                        .foregroundStyle(Yx.onCopper)
                        .frame(maxWidth: .infinity, minHeight: 48)
                        .background(Yx.copper, in: Capsule())
                }
                .buttonStyle(.plain)
                .disabled(busy)
            }
        }
        .padding(.horizontal, 16).padding(.vertical, 14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Yx.low, in: RoundedRectangle(cornerRadius: Yx.cardRadius, style: .continuous))
    }
}

// MARK: - 小件

private struct Chip: View {
    let text: String
    let color: Color
    var body: some View {
        Text(text).font(.mono(12)).foregroundStyle(color)
            .padding(.horizontal, 9).padding(.vertical, 3)
            .background(color.opacity(0.16), in: Capsule())
    }
}

private struct Code: View {
    let text: String
    let color: Color
    init(_ text: String, _ color: Color) { self.text = text; self.color = color }
    var body: some View {
        Text(text).font(.mono(13)).foregroundStyle(color)
            .textSelection(.enabled)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct Block<Content: View>: View {
    let content: Content
    init(@ViewBuilder content: () -> Content) { self.content = content() }
    var body: some View {
        VStack(alignment: .leading, spacing: 0) { content }
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Yx.lowest, in: RoundedRectangle(cornerRadius: Yx.blockRadius, style: .continuous))
            .padding(.top, 9)
    }
}

private struct PathRow: View {
    let path: String
    init(_ path: String) { self.path = path }
    var body: some View {
        if !path.isEmpty {
            ScrollView(.horizontal, showsIndicators: false) {
                Text(path).font(.mono(13)).foregroundStyle(Yx.onSurface)
            }
            .padding(.top, 7)
        }
    }
}

private func base(_ p: String) -> String { p.split(separator: "/").last.map(String.init) ?? p }

/// `Exit code N`，只在**开头**才算。不用正则 —— 就是个前缀判断。
private func exitCode(_ s: String?) -> String? {
    guard let s, s.hasPrefix("Exit code ") else { return nil }
    let digits = s.dropFirst("Exit code ".count).prefix(while: \.isNumber)
    return digits.isEmpty ? nil : String(digits)
}
