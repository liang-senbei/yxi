import Foundation

/// 会话状态。语义跟服务器上 `cc-state` 写进 `~/.cloud-status/*.json` 的一致。
public enum SessionState: String, CaseIterable, Sendable {
    case needsYou, working, done, idle

    public var label: String {
        switch self {
        case .needsYou: return "等你"
        case .working:  return "干活中"
        case .done:     return "已完成"
        case .idle:     return "空闲"
        }
    }

    public static func of(_ raw: String?) -> SessionState {
        switch raw {
        case "input": return .needsYou
        case "work":  return .working
        case "done":  return .done
        default:      return .idle
        }
    }
}

public struct BoardSession: Identifiable, Equatable, Sendable {
    public let name: String
    public let windows: Int
    public let attached: Bool
    public let cwd: String
    public let lastActivity: Date
    public let state: SessionState
    /// `cc-state` 写的一句话：「运行命令: …」「等待你(决策/输入)」之类
    public let detail: String

    public init(
        name: String, windows: Int, attached: Bool, cwd: String,
        lastActivity: Date, state: SessionState, detail: String
    ) {
        self.name = name; self.windows = windows; self.attached = attached
        self.cwd = cwd; self.lastActivity = lastActivity
        self.state = state; self.detail = detail
    }

    /// ⚠️ 用会话名当身份，**不要用数组下标** —— 换组时下标会变，
    /// SwiftUI 会把「移动」渲染成「删掉再插入」，卡片直接闪一下。
    public var id: String { name }

    /// 去掉 `cc-` 前缀的短名，界面上用
    public var short: String { name.hasPrefix("cc-") ? String(name.dropFirst(3)) : name }
}

/// 一次 SSH 往返拿到全部会话信息。
///
/// **抄 Moshi 的两个做法**（PRD §1.2 / 附录 C.1）：
///   1. **一次往返拿全部** —— 手机网络下往返成本高，20 个会话不能开 20 次连接
///   2. **带版本号的 marker 分段** —— 主机侧脚本以后改了、App 还是旧的，
///      能优雅降级（缺段当空）而不是解析崩掉
///
/// 还有第三个：**不假设主机上有 jq / python**，只用 `printf` 和 `cat` ——
/// 这是 Moshi 自己在脚本注释里写明的理由，很实在：目标机可能什么都没装。
///
/// ⚠️ 这里只出**命令字符串 + 解析**，不碰 SSH。这样这一层能在 Linux 上跑测试，
/// 而测试数据就是这台机器上真跑一遍这段脚本的原样输出。
public enum SessionProbe {

    public static let marker = "__YXI_SNAPSHOT_V1__"

    public static let snapshotScript = #"""
    m=__YXI_SNAPSHOT_V1__
    s(){ printf '%s\t%s\n' "$m" "$1"; }
    s tmux_begin
    tmux list-sessions -F '#{session_name}|#{session_windows}|#{session_activity}|#{session_attached}|#{pane_current_path}' 2>/dev/null || true
    s tmux_end
    s status_begin
    for f in $HOME/.cloud-status/*.json; do [ -f "$f" ] && cat "$f" && echo; done 2>/dev/null || true
    s status_end
    """#

    public static func parseSnapshot(_ out: String) -> [BoardSession] {
        let tmux = extract(out, "tmux")
        let status = extract(out, "status")

        // 状态先建索引：会话名 → (state, detail)
        var states: [String: (String, String)] = [:]
        for line in status.components(separatedBy: "\n") where !line.isBlank {
            // ⚠️ `~/.cloud-status/` 里真的躺着内容是 `{}` 的文件（实测），
            // 还有些 json 对应的 tmux 会话早没了。两种都得安静跳过，不能崩也不能造出幽灵行。
            guard let o = JSON.parse(line: line), o.isObject else { continue }
            let name = o["session"].string
            if !name.isEmpty { states[name] = (o["state"].string, o["detail"].string) }
        }

        return tmux.components(separatedBy: "\n").compactMap { line in
            let p = line.components(separatedBy: "|")
            guard p.count >= 5 else { return nil }
            let st = states[p[0]]
            return BoardSession(
                name: p[0],
                windows: Int(p[1]) ?? 1,
                attached: p[3] != "0",
                cwd: p[4],
                lastActivity: Date(timeIntervalSince1970: Double(p[2]) ?? 0),
                state: SessionState.of(st?.0),
                detail: st?.1 ?? ""
            )
        }
    }

    /// 只要 `<marker>\tX_begin` 和 `<marker>\tX_end` 之间的内容。
    /// **缺段就当空，不抛异常** —— 这就是 marker 分段的意义所在。
    private static func extract(_ out: String, _ name: String) -> String {
        let begin = "\(marker)\t\(name)_begin"
        let end = "\(marker)\t\(name)_end"
        guard let a = out.range(of: begin),
              let b = out.range(of: end, range: a.upperBound..<out.endIndex)
        else { return "" }
        return String(out[a.upperBound..<b.lowerBound])
            .trimmingCharacters(in: CharacterSet(charactersIn: "\r\n"))
    }

    /// 给某个会话发一句话。`hub say` 已经验证过这条路走得通。
    ///
    /// ⚠️ **分两步：先送文本、再单独送回车。** 合成一条时，文本里若含特殊字符
    /// 会让 `send-keys` 把它当按键名解析 —— 比如 `Enter` 这三个字就会变成一次回车。
    public static func sendCommands(target: String, text: String) -> [String] {
        let q = text.replacingOccurrences(of: "'", with: "'\\''")
        let t = target.replacingOccurrences(of: "'", with: "'\\''")
        return ["tmux send-keys -t '\(t)' -l '\(q)'", "tmux send-keys -t '\(t)' Enter"]
    }

    /// 允许送的按键。⚠️ 白名单，因为 key 最终会拼进 shell 命令、
    /// 而且会变成**打进别人服务器**的按键。不在白名单里就返回 nil，调用方什么都别做。
    private static let safeKey = try! Regex(#"[0-9]{1,2}|Up|Down|Left|Right|Enter|Escape"#)

    /// 送**一个按键**（不带回车）—— 点选项就靠它。
    /// 协议实测：单选送数字即选中并确认；多选送数字是切换勾选，
    /// 要再送 `Right` 跳到 Submit 页、送 `1` 才算提交（TROUBLESHOOTING #29）。
    public static func keyCommand(target: String, key: String) -> String? {
        guard key.wholeMatch(of: safeKey) != nil else { return nil }
        let t = target.replacingOccurrences(of: "'", with: "'\\''")
        return "tmux send-keys -t '\(t)' '\(key)'"
    }

    /// 抓某个会话最近 lines 行屏幕。
    public static func peekCommand(target: String, lines: Int = 60) -> String {
        let t = target.replacingOccurrences(of: "'", with: "'\\''")
        return "tmux capture-pane -p -t '\(t)' 2>/dev/null | tail -\(lines)"
    }

    /// 一次抓屏，把「等你选」和「此刻在忙什么」一起解出来。
    ///
    /// ⚠️ 合成一次是有意的：两边都要抓屏，分两次不但多一个来回，
    /// 还会**看到两个不同时刻的屏幕** —— 状态和待答对不上，
    /// 表现成偶发的闪烁，非常难查。
    public static func readScreen(_ screen: String) -> (Pending?, Live) {
        (Prompt.parse(screen), Live.parse(screen))
    }
}
