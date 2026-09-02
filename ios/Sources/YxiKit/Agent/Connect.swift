import Foundation

/// **连接** —— 把第三方服务接给连着那台机器上的 agent。跟安卓 `agent/Connect.kt` 同一套命令和解析，
/// 设计和三个关键事实见 TROUBLESHOOTING #198。
///
///  · GitHub：`gh auth login` 设备码。一次登录，git 推拉 + GitHub MCP 一起有。
///  · 远程 MCP：`claude mcp add` + `claude mcp login --no-browser`，授权 URL 打在 tmux 屏幕上。
///    ⚠️ iOS 这边**没有端口转发**（Citadel 只给积木），授权完浏览器停在 localhost 页面，
///    把地址粘回来（Claude Code 明确接受 `Or paste the redirect URL here:`）。
public enum Connect {

    public enum Kind: Sendable { case gh, mcp, info }

    public struct Service: Identifiable, Equatable, Sendable {
        public let key: String
        public let name: String
        /// 接上之后 agent 能干什么
        public let what: String
        public let url: String
        public let transport: String
        public let kind: Kind
        /// 不用登录就能用
        public let noAuth: Bool
        public var id: String { key }
        public init(_ key: String, _ name: String, _ what: String, url: String = "", transport: String = "http",
                    kind: Kind = .mcp, noAuth: Bool = false) {
            self.key = key; self.name = name; self.what = what; self.url = url
            self.transport = transport; self.kind = kind; self.noAuth = noAuth
        }
    }

    /// ⚠️ 地址都在服务器上探过活（2026-09-02）。跟安卓那份保持一致。
    public static let catalog: [Service] = [
        Service("github", "GitHub", "git 推拉、仓库 / PR / Issue", kind: .gh),
        Service("notion", "Notion", "读写页面和数据库", url: "https://mcp.notion.com/mcp"),
        Service("linear", "Linear", "工单", url: "https://mcp.linear.app/mcp"),
        Service("sentry", "Sentry", "错误监控", url: "https://mcp.sentry.dev/mcp"),
        Service("atlassian", "Jira / Confluence", "Atlassian", url: "https://mcp.atlassian.com/v1/sse", transport: "sse"),
        Service("asana", "Asana", "任务", url: "https://mcp.asana.com/sse", transport: "sse"),
        Service("stripe", "Stripe", "支付", url: "https://mcp.stripe.com"),
        Service("paypal", "PayPal", "支付", url: "https://mcp.paypal.com/mcp"),
        Service("zapier", "Zapier", "几千个应用的自动化", url: "https://mcp.zapier.com/api/mcp/mcp"),
        Service("vercel", "Vercel", "部署", url: "https://mcp.vercel.com"),
        Service("netlify", "Netlify", "部署", url: "https://netlify-mcp.netlify.app/mcp"),
        Service("cloudflare", "Cloudflare", "域名 / Workers", url: "https://mcp.cloudflare.com/mcp"),
        Service("supabase", "Supabase", "数据库", url: "https://mcp.supabase.com/mcp"),
        Service("figma", "Figma", "设计稿", url: "https://mcp.figma.com/mcp"),
        Service("canva", "Canva", "设计", url: "https://mcp.canva.com/mcp"),
        Service("intercom", "Intercom", "客服", url: "https://mcp.intercom.com/mcp"),
        Service("monday", "monday.com", "项目", url: "https://mcp.monday.com/sse", transport: "sse"),
        Service("box", "Box", "文件", url: "https://mcp.box.com"),
        Service("huggingface", "Hugging Face", "模型 / 数据集", url: "https://huggingface.co/mcp", noAuth: true),
        Service("context7", "Context7", "各种库的最新文档", url: "https://mcp.context7.com/mcp", noAuth: true),
        Service("deepwiki", "DeepWiki", "读任何 GitHub 仓库的文档", url: "https://mcp.deepwiki.com/mcp", noAuth: true),
        Service("claudeai", "Gmail / 日历 / Slack …", "只有 claude.ai 订阅登录才有（claude.ai 连接器），用 API key 的没有", kind: .info),
        Service("chrome", "Claude in Chrome", "浏览器插件只配同一台电脑上的 Claude Code，远程服务器用不了", kind: .info),
    ]

    public enum Health: Sendable { case connected, needsAuth, failed, absent }

    public struct Status: Sendable {
        public var ghUser: String?
        public var ghInstalled = true
        public var claudeInstalled = true
        public var mcp: [String: Health] = [:]
        public init() {}
        public func of(_ s: Service) -> Health {
            switch s.kind {
            case .gh: return ghUser != nil ? .connected : .absent
            case .mcp: return mcp[s.key] ?? .absent
            case .info: return .absent
            }
        }
    }

    // MARK: 命令

    /// 一趟拿全部。⚠️ `claude mcp list` 会挨个探活，几秒。
    public static let statusCommand =
        "echo __GH__; command -v gh >/dev/null 2>&1 && gh auth status -h github.com 2>&1 || echo NO_GH; "
        + "echo __MCP__; command -v claude >/dev/null 2>&1 && claude mcp list 2>/dev/null || echo NO_CLAUDE; echo __END__"

    public static func parseStatus(_ out: String) -> Status {
        var st = Status()
        let gh = out.between("__GH__", "__MCP__")
        let mcpText = out.between("__MCP__", "__END__")
        st.ghUser = first(#"Logged in to github\.com account (\S+)"#, in: gh)
        st.ghInstalled = !gh.contains("NO_GH")
        st.claudeInstalled = !mcpText.contains("NO_CLAUDE")
        for raw in mcpText.components(separatedBy: "\n") {
            let line = raw.trimmingCharacters(in: .whitespaces)
            guard let name = first(#"^([^:\s]+): \S+ \(\w+\) - (.+)$"#, in: line),
                  let tail = first(#"^([^:\s]+): \S+ \(\w+\) - (.+)$"#, in: line, group: 2) else { continue }
            let t = tail.lowercased()
            st.mcp[name] = t.contains("needs authentication") ? .needsAuth
                : (t.contains("connected") && !t.contains("failed")) ? .connected : .failed
        }
        return st
    }

    public static func tmux(for key: String) -> String {
        "yxi-auth-" + key.map { $0.isLetter || $0.isNumber || $0 == "_" || $0 == "-" ? String($0) : "_" }.joined()
    }

    /// `-J` 把折行接回去 —— 授权 URL 三百多字符必然折
    public static func peekCommand(_ tmux: String) -> String { "tmux capture-pane -p -J -t '\(tmux)' 2>/dev/null" }
    public static func enterCommand(_ tmux: String) -> String { "tmux send-keys -t '\(tmux)' Enter" }
    public static func killCommand(_ tmux: String) -> String { "tmux kill-session -t '\(tmux)' 2>/dev/null; true" }

    public static func ghLoginStart() -> String {
        let t = tmux(for: "github")
        return "tmux kill-session -t '\(t)' 2>/dev/null; tmux new-session -d -s '\(t)' -x 120 -y 30 "
            + "'gh auth login -h github.com -p https -w; echo __DONE__$?; sleep 900'"
    }
    public static func ghAsksGit(_ pane: String) -> Bool { pane.contains("(Y/n)") }
    public static func ghCode(_ pane: String) -> String? { first(#"one-time code: ([A-Z0-9]{4}-[A-Z0-9]{4})"#, in: pane) }
    public static func ghAsksOpen(_ pane: String) -> Bool { pane.contains("Press Enter to open") }
    public static let ghDeviceURL = "https://github.com/login/device"

    /// 登录后：gh 配成 git 凭据助手 + GitHub 官方远程 MCP 加给 Claude Code（token 用 gh 的）
    public static let ghAfterCommand =
        "gh auth setup-git >/dev/null 2>&1; T=$(gh auth token 2>/dev/null); "
        + "if [ -n \"$T\" ] && command -v claude >/dev/null 2>&1; then "
        + "claude mcp remove -s user github >/dev/null 2>&1; "
        + "claude mcp add --transport http -s user github https://api.githubcopilot.com/mcp/ "
        + "--header \"Authorization: Bearer $T\" >/dev/null 2>&1; fi; echo ok"
    public static let ghLogout = "gh auth logout -h github.com >/dev/null 2>&1; claude mcp remove -s user github >/dev/null 2>&1; echo ok"

    public static func mcpAdd(_ s: Service) -> String {
        "claude mcp get \(shq(s.key)) >/dev/null 2>&1 || claude mcp add --transport \(s.transport) -s user \(shq(s.key)) \(shq(s.url)) 2>&1 | tail -1"
    }
    public static func mcpRemove(_ key: String) -> String { "claude mcp remove -s user \(shq(key)) 2>&1 | tail -1" }
    public static func mcpLoginStart(_ key: String) -> String {
        let t = tmux(for: key)
        return "tmux kill-session -t '\(t)' 2>/dev/null; tmux new-session -d -s '\(t)' -x 220 -y 40 "
            + "'claude mcp login \(shq(key)) --no-browser; echo __DONE__$?; sleep 900'"
    }
    public static func loginURL(_ pane: String) -> String? {
        first(#"(https://\S+redirect_uri\S*)"#, in: pane)?.trimmingCharacters(in: CharacterSet(charactersIn: ".,)"))
    }
    public static func callbackPort(_ url: String) -> Int? { first(#"localhost(?:%3A|:)(\d+)"#, in: url).flatMap(Int.init) }
    /// 文本和回车分两条、中间 sleep（#174）
    public static func pasteCommand(key: String, redirectURL: String) -> String {
        let t = tmux(for: key)
        return "tmux send-keys -t '\(t)' -l \(shq(redirectURL)); sleep 0.4; tmux send-keys -t '\(t)' Enter"
    }
    public static func parseDone(_ pane: String) -> Bool? { first(#"__DONE__(\d+)"#, in: pane).map { $0 == "0" } }
    public static func failReason(_ pane: String) -> String {
        pane.components(separatedBy: "\n").last { $0.contains("Couldn't complete") || $0.lowercased().contains("error") }?
            .trimmingCharacters(in: .whitespaces).prefix(160).description ?? "没成功"
    }

    // MARK: 小工具

    static func shq(_ s: String) -> String { "'" + s.replacingOccurrences(of: "'", with: "'\\''") + "'" }

    static func first(_ pattern: String, in s: String, group: Int = 1) -> String? {
        guard let re = try? NSRegularExpression(pattern: pattern, options: [.anchorsMatchLines]),
              let m = re.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)),
              let r = Range(m.range(at: group), in: s) else { return nil }
        return String(s[r])
    }
}

private extension String {
    func between(_ a: String, _ b: String) -> String {
        guard let ra = range(of: a) else { return "" }
        let rest = self[ra.upperBound...]
        guard let rb = rest.range(of: b) else { return String(rest) }
        return String(rest[..<rb.lowerBound])
    }
}
