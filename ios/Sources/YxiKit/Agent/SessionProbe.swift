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
    s tr_begin
    find "$HOME/.claude/projects" -maxdepth 2 -name '*.jsonl' -printf '%h\t%T@\n' 2>/dev/null | awk -F'\t' '{n=split($1,a,"/"); d=a[n]; t=int($2); if(t>m[d]) m[d]=t} END{for(k in m) printf "%s\t%d\n", k, m[k]}' 2>/dev/null || true
    s tr_end
    """#

    public static func parseSnapshot(_ out: String) -> [BoardSession] {
        let tmux = extract(out, "tmux")
        let status = extract(out, "status")
        // 转录最后写入时间 —— 「上次对话」的真来源，见 lastActivityOf
        let transcripts = parseTranscriptTimes(extract(out, "tr"))

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
                lastActivity: Date(timeIntervalSince1970: lastActivityOf(
                    tmuxTs: Double(p[2]) ?? 0, cwd: p[4], transcripts: transcripts)),
                state: SessionState.of(st?.0),
                detail: st?.1 ?? ""
            )
        }
    }

    /// `<项目目录名>\t<unix秒>` 一行一条 → 表。解析不了的行忽略。
    public static func parseTranscriptTimes(_ raw: String) -> [String: Double] {
        var m: [String: Double] = [:]
        for line in raw.components(separatedBy: "\n") {
            guard let i = line.firstIndex(of: "\t") else { continue }
            let key = String(line[line.startIndex..<i]).trimmingCharacters(in: .whitespaces)
            let ts = Double(String(line[line.index(after: i)...]).trimmingCharacters(in: .whitespaces)) ?? 0
            if ts > 0, !key.isEmpty { m[key] = ts }
        }
        return m
    }

    /// 这个会话**上次真正对话**是什么时候。
    ///
    /// ⚠️ **不能只信 tmux 的 `session_activity`。** 安卓侧实测（用户报「显示那么久之前，
    /// 很多不是刚对话吗」）：`claude_desktop` 的 tmux 活动写着 **2 天前**、
    /// cc-state 的 ts 更离谱写着 **7 天前**，而它的转录**一分钟前**还在写。
    /// **转录文件的 mtime 才是权威** —— Claude Code 每说一句都在写它。
    /// 见安卓侧 TROUBLESHOOTING #130。
    public static func lastActivityOf(tmuxTs: Double, cwd: String, transcripts: [String: Double]) -> Double {
        let tr = transcripts[Transcript.projectDirOf(cwd)] ?? 0
        return max(tmuxTs, tr)
    }

    /// **盯屏幕：变了才推。** 一条长连通道，服务器侧自己比对，没变不过网。
    ///
    /// ⚠️ 为什么不轮询：轮询是「每次都要问一遍」，每问一次就是一个 SSH 往返。
    /// 安卓侧实测抓屏本身 **0 毫秒**，延迟几乎全在往返上；改成推之后
    /// 变化到达约 **200ms**（TROUBLESHOOTING #134）。
    /// ⚠️ `|| exit` 不能省 —— 会话没了要让远端循环自己退，否则留一堆空转的壳。
    public static func watchScreenCommand(target: String, lines: Int = 60) -> String {
        let q = target.replacingOccurrences(of: "'", with: "'\\''")
        return "trap 'exit' PIPE HUP TERM INT; prev=''; while :; do "
            + "cur=$(tmux capture-pane -pt '\(q)' -S -\(lines) 2>/dev/null) || exit; "
            + "if [ \"$cur\" != \"$prev\" ]; then "
            + "printf '%s\\n\(screenMarker)\\n' \"$cur\" || exit; prev=$cur; fi; "
            + "sleep 0.2; done"
    }

    /// 盯屏推流里，一屏结束的标记行。
    public static let screenMarker = "__YXI_SCR__"

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

// MARK: - 未提交的改动

extension SessionProbe {
    /// 审批「让它改 / 提交」之前，一眼看清 Claude 到底动了什么。
    ///
    /// `--stat` 摘要 + 具体 diff（封顶 60k，手机上够看了）。
    /// ⚠️ 不是 git 仓库 / 没改动都要给**一句人话**，不能空着 —— 空白屏说明不了任何事。
    public static func gitDiffCommand(cwd: String) -> String {
        let safe = cwd.replacingOccurrences(of: "'", with: "'\\''")
        return "cd '\(safe)' 2>/dev/null && "
            + "{ s=$(git diff --stat 2>/dev/null); d=$(git diff 2>/dev/null | head -c 60000); "
            + "if [ -z \"$s\" ]; then echo '（没有未提交的改动）'; "
            + "else printf '%s\\n\\n%s' \"$s\" \"$d\"; fi; } "
            + "|| echo '（这里不是 git 仓库）'"
    }
}

// MARK: - 「多久没动了」

/// 相对时间。跟安卓 `ui/TimeFmt.ago` 一致。
///
/// ⚠️ **来源是转录文件的 mtime，不是 tmux 的 `session_activity`**（#130）——
/// 后者会因为 tmux 自己的刷新而更新，看起来"刚活动过"其实早就停了。
public func ago(_ date: Date, now: Date = Date()) -> String {
    let s = Int(now.timeIntervalSince(date))
    switch s {
    case ..<0:      return "刚刚"          // 服务器时钟比手机快一点，别显示"-3 秒前"
    case ..<60:     return "刚刚"
    case ..<3600:   return "\(s / 60) 分钟前"
    case ..<86400:  return "\(s / 3600) 小时前"
    case ..<(86400 * 30): return "\(s / 86400) 天前"
    default:        return "很久以前"
    }
}

// MARK: - 新开一个会话

extension SessionProbe {
    /// 目录名 → tmux 会话名。跟安卓 `SessionsScreen` 的取名规则一致
    /// （`/opt/workspace/Yxi` → `cc-Yxi`），这样两端看到的是同一批会话名。
    ///
    /// ⚠️ 只留 `[字母数字._-]`：tmux 的会话名里带空格、冒号、点号会很难伺候
    /// （`:` 是它 target 语法的分隔符）。滤空了就退到 `work`，别产出 `cc-`。
    public static func sessionName(forDir dir: String) -> String {
        let base = dir.reversed().drop { $0 == "/" }.reversed()
        let last = base.split(separator: "/").last.map(String.init) ?? ""
        let safe = last.filter { $0.isLetter || $0.isNumber || "._-".contains($0) }
        return "cc-" + (safe.isEmpty ? "work" : safe)
    }

    /// **有就直接用，没有才新建**，新建时在那个目录里把 `claude` 跑起来。
    /// ⚠️ 幂等：重复点不会开出第二个同名会话，也不会把已有会话里的活打断。
    public static func newSessionCommand(dir: String) -> String {
        let name = sessionName(forDir: dir)
        let d = dir.replacingOccurrences(of: "'", with: "'\\''")
        let n = name.replacingOccurrences(of: "'", with: "'\\''")
        return "tmux has-session -t '\(n)' 2>/dev/null || "
            + "{ tmux new-session -d -s '\(n)' -c '\(d)'; tmux send-keys -t '\(n)' 'claude' Enter; }"
    }
}
