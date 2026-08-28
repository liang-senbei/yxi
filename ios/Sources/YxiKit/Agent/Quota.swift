import Foundation

/// 真正的**订阅额度**：5 小时窗口用掉了几成、这一周用掉了几成、什么时候重置。
///
/// ⚠️⚠️ **跟 [Usage] 不是一回事，两个都要，别混**（TROUBLESHOOTING #117 / #122）：
///   · [Usage] 走 `ccusage`，读那台机器 `~/.claude` 里的会话日志，算出来的是
///     **token 数和花掉的钱** —— 它压根不知道订阅被用掉了几成。
///   · 这里是**账号级的订阅配额**，只有 Anthropic 服务端知道，
///     转录里没有、本地日志也算不出来。
///
/// ⚠️ **拿法是 `claude -p "/usage"`**（非交互打印模式），**不是去某个会话里刮屏**。
/// 三个好处都实测确认过（#117）：
///   · **不用借会话** —— 早先的做法要借一个空闲会话跑 `/usage`，可用户机器上
///     会话全忙 / 输入框都有草稿时一个都借不到，额度就永远查不了（用户真撞上了）。
///   · **不打扰任何东西** —— 不往任何会话发键、不动任何 tmux 面板。
///   · **不花钱** —— `/usage` 是本地命令，`-p` 打印完就退，不调模型。
///
/// ⚠️ 跟 [Usage] 同一条纪律：**拿不到就返回 nil，调用方整块藏掉**。
/// 不显示 0、不显示「未知」、不画空进度条 —— 额度这种数字**你会照着它安排
/// 今天开不开大活**，一个假的 0 比看不见糟得多（TROUBLESHOOTING #51）。
public struct Quota: Codable, Equatable, Sendable {

    /// 5 小时窗口已经用掉的百分比（0–100）
    public let sessionPercent: Int
    /// `Aug 28, 5:19pm (UTC)`。读不到就是空串 —— 那一行的 `· resets …` 是可选的。
    public let sessionResets: String
    /// 本周（所有模型）已经用掉的百分比（0–100）
    public let weekPercent: Int
    public let weekResets: String
    /// 订阅档位，如 `Max 20x` / `Max 5x` / `Pro`。读不到就空串（界面只是少一个小标签）。
    public let plan: String

    public init(sessionPercent: Int, sessionResets: String,
                weekPercent: Int, weekResets: String, plan: String = "") {
        self.sessionPercent = sessionPercent; self.sessionResets = sessionResets
        self.weekPercent = weekPercent; self.weekResets = weekResets
        self.plan = plan
    }

    /// 跑一次就把额度和档位一起拿回来。**不碰任何会话。**
    ///
    /// ⚠️ PATH 要显式补 `~/.local/bin`（claude 装在那儿）和 node ——
    /// **非交互 SSH 的 PATH 常常是残的**，而 `claude` 是个 node 脚本。
    /// ⚠️ **别加 `IS_SANDBOX`**：那是给交互式 `--dangerously-skip-permissions`
    /// （root 限制）用的，`-p` 打印模式不需要，加了反而可能改行为。
    /// ⚠️ `timeout 30`：连服务端拿额度够用了，再久就是卡住 ——
    /// 别让界面上的「查着…」转到天荒地老。
    /// 没装 claude 就 `exit 0`（输出空），[parse] 据此返回 nil。
    ///
    /// ⚠️⚠️ 档位那一段**只 `grep` 两个字段，绝不整个 `cat` credentials**（#122）：
    /// 那文件里还有 access / refresh token，不该出现在任何日志、抓屏或缓存里。
    public static let probeCommand =
        "export PATH=$HOME/.local/bin:$HOME/.npm-global/bin:/usr/local/bin:$PATH; " +
        "for d in /opt/node*/bin; do [ -d \"$d\" ] && PATH=$PATH:$d; done; " +
        "command -v claude >/dev/null 2>&1 || exit 0; " +
        "grep -oE '\"(rateLimitTier|subscriptionType)\":\"[^\"]*\"' $HOME/.claude/.credentials.json 2>/dev/null; " +
        "timeout 30 claude -p '/usage' 2>/dev/null"

    /// ⚠️⚠️ **锚点是两行标题的文字，不是行号、更不是「第几个 %」。**
    /// 真机输出里紧接着还有一行 `Current week (Fable): 0% used` ——
    /// 它也带 `% used`，按顺序数百分号会把本周额度读成 0（#117）。
    /// `resets` 那半截是**可选**的：Fable 那行就没有。
    private static let sessionLine = try! Regex(#"Current session:\s*(\d{1,3})%\s*used(?:\s*·\s*resets\s*(.+))?"#)
    private static let weekLine = try! Regex(#"Current week \(all models\):\s*(\d{1,3})%\s*used(?:\s*·\s*resets\s*(.+))?"#)

    /// - Parameter out: [probeCommand] 的原样输出（credentials 里那两行 + `/usage` 的打印）
    /// - Returns: nil = 那台机器上没装 claude / 没登录 / 输出格式变了。
    ///            **调用方必须把整块藏掉**，不许拿它当 0 显示。
    public static func parse(_ out: String) -> Quota? {
        guard let s = out.firstMatch(of: sessionLine),
              let w = out.firstMatch(of: weekLine),
              let sp = s[1].substring.flatMap({ Int($0) }),
              let wp = w[1].substring.flatMap({ Int($0) })
        else { return nil }
        return Quota(
            sessionPercent: sp,
            sessionResets: trimmed(s[2].substring),
            weekPercent: wp,
            weekResets: trimmed(w[2].substring),
            // 档位藏在同一段输出里 —— probeCommand 把 credentials 那两行一起打出来了
            plan: plan(of: out)
        )
    }

    private static func trimmed(_ s: Substring?) -> String {
        (s.map(String.init) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
    }

    // ⚠️ 档位**不在 `/usage` 的输出里**（它只说 "using your subscription"）——
    // 在 `~/.claude/.credentials.json` 里（#122）。
    private static let tier = try! Regex(#""rateLimitTier"\s*:\s*"([^"]*)""#)
    private static let subscriptionType = try! Regex(#""subscriptionType"\s*:\s*"([^"]*)""#)
    private static let maxTimes = try! Regex(#"max_?(\d+)x"#)

    /// `default_claude_max_20x` → `Max 20x`；含 `pro` → `Pro`；
    /// 都没有就拿 `subscriptionType`（`max` / `pro`）兜底；再没有就空串。
    static func plan(of credentialsJSON: String) -> String {
        let t = credentialsJSON.firstMatch(of: tier)?[1].substring.map(String.init) ?? ""
        if let n = t.firstMatch(of: maxTimes)?[1].substring { return "Max \(n)x" }
        if t.contains("pro") { return "Pro" }
        switch credentialsJSON.firstMatch(of: subscriptionType)?[1].substring {
        case "max": return "Max"
        case "pro": return "Pro"
        default: return ""
        }
    }
}
