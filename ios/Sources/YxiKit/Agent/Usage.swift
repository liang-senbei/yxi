import Foundation

/// 用量：**读那台机器自己的 `~/.claude`**，所以天然按服务器分开
/// —— 你在 station 上烧掉的额度不会算进本机（PRD 附录 K）。
///
/// ⚠️ **探测不到 `ccusage` 就什么都不显示。** 不显示 0，不显示「未知」，
/// 整块藏起来 —— 额度这种东西，**显示一个假的比不显示危险得多**：
/// 你会照着那个数字安排今天要不要开大活（TROUBLESHOOTING #51）。
public struct Usage: Codable, Equatable, Sendable {
    /// 5 小时窗口还剩多少分钟
    public let remainingMinutes: Int
    /// 这个窗口已经过去的百分比（0–100）
    public let elapsedPercent: Int
    public let tokens: Int64
    public let costUSD: Double
    public let tokensPerMinute: Double

    public init(remainingMinutes: Int, elapsedPercent: Int, tokens: Int64,
                costUSD: Double, tokensPerMinute: Double) {
        self.remainingMinutes = remainingMinutes; self.elapsedPercent = elapsedPercent
        self.tokens = tokens; self.costUSD = costUSD; self.tokensPerMinute = tokensPerMinute
    }

    public var remainText: String {
        String(format: "%dh%02dm", remainingMinutes / 60, remainingMinutes % 60)
    }
    public var tokenText: String {
        if tokens >= 1_000_000 { return String(format: "%.1fM", Double(tokens) / 1e6) }
        if tokens >= 1_000 { return String(format: "%.0fK", Double(tokens) / 1e3) }
        return "\(tokens)"
    }

    /// ⚠️ PATH 要补上 `~/.local/bin` 和 npm 的全局目录 —— **非交互 shell 的 PATH 常常是残的**，
    /// `ccusage` 明明装了却 `command -v` 不到。
    /// 没装就直接 `exit 0`（输出空），调用方据此把整块藏掉。
    public static let probeCommand =
        "export PATH=$HOME/.local/bin:$HOME/.npm-global/bin:/usr/local/bin:$PATH; " +
        "command -v ccusage >/dev/null 2>&1 || exit 0; " +
        "ccusage blocks --active --json 2>/dev/null"

    /// - Parameter out: [probeCommand] 的原样输出（`ccusage blocks --active --json`）
    /// - Returns: nil = 这台机器上没有 ccusage / 当前窗口没有活动块 / 数据读不出来。
    ///            **调用方必须把整块藏掉**，不许拿它当 0 显示。
    ///
    /// schema 不是猜的，是照着本机 `~/.local/bin/cc-quota` 里那段能跑的 python 抄的，
    /// 并且拿真机上 `ccusage blocks --active --json` 的原样输出核对过。
    public static func parse(_ out: String) -> Usage? {
        let s = out.trimmingCharacters(in: .whitespacesAndNewlines)
        guard s.hasPrefix("{"), let root = JSON.parse(line: s) else { return nil }
        // ⚠️ 当前 5 小时窗口空闲时 ccusage 返回的是 `{"blocks": []}`（station 上实测）——
        // 那不是错误，但也**没有任何可显示的数字**，一样返回 nil。
        let b = root["blocks"][0]
        guard b.isObject else { return nil }

        let tc = b["tokenCounts"]
        // ⚠️ 非活动块的 `projection` 和 `burnRate` 是 **null**，不是缺字段（station 上实测）。
        // 没有 `remainingMinutes` 就**整块不显示** —— 界面上最显眼的那个数字是
        // 「距重置 xhxxm」，拿不到就只能写 `0h00m`，而那是个假的：
        // 你会照着「额度马上重置」去安排今天开不开大活（TROUBLESHOOTING #51）。
        // 用量宁可整块藏掉，也不能显示一个假的 0。
        guard b["projection"]["remainingMinutes"].exists else { return nil }
        let remain = b["projection"]["remainingMinutes"].int
        // 5 小时 = 300 分钟；剩多少反推过了多少
        let pct = (remain >= 0 && remain <= 300) ? (300 - remain) * 100 / 300 : 0
        let total = b["totalTokens"].int64
        return Usage(
            remainingMinutes: max(remain, 0),
            elapsedPercent: pct,
            tokens: total > 0 ? total : tc["inputTokens"].int64 + tc["outputTokens"].int64,
            costUSD: b["costUSD"].double,
            tokensPerMinute: b["burnRate"]["tokensPerMinute"].double
        )
    }
}
