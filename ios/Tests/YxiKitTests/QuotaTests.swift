import XCTest
@testable import YxiKit

/// 订阅额度的解析。
///
/// ⚠️ **下面 [real] 是本机 `claude -p '/usage'` 的输出原样抠下来的**（2026-08-28 实测，
/// Claude Code 在 `~/.local/bin/claude`），一个字没改；开头那两行是同一条
/// `probeCommand` 里 `grep` credentials 打出来的，**顺序和真跑出来的一致**。
///
/// 为什么非要真样本：手写样例只能证明「解析器认得我以为的格式」。
/// 真输出里的 `Current week (Fable): 0% used`、后面整段「What's contributing」说明文字
/// 里那一堆 `95% of your usage …` —— 没有一条是想得出来的，而它们**每一条都能把
/// 「数第几个百分号」那种写法带沟里**（TROUBLESHOOTING #117）。
final class QuotaTests: XCTestCase {

    private let real = #"""
"subscriptionType":"max"
"rateLimitTier":"default_claude_max_20x"
You are currently using your subscription to power your Claude Code usage

Current session: 11% used · resets Aug 28, 5:19pm (UTC)
Current week (all models): 28% used · resets Aug 30, 12:59pm (UTC)
Current week (Fable): 0% used

What's contributing to your limits usage?
Approximate, based on local sessions on this machine — does not include other devices or claude.ai. Behaviors are independent characteristics, not a breakdown.

Last 24h · 949 requests · 8 sessions
  95% of your usage was at >150k context
  84% of your usage came from subagent-heavy sessions
  84% of your usage came from sessions active for 8+ hours

Last 7d · 6086 requests · 37 sessions
  97% of your usage came from sessions active for 8+ hours
  95% of your usage was at >150k context
  82% of your usage came from subagent-heavy sessions
"""#

    func test_解出两档额度和重置时间() throws {
        let q = try XCTUnwrap(Quota.parse(real))
        XCTAssertEqual(q.sessionPercent, 11)
        XCTAssertEqual(q.sessionResets, "Aug 28, 5:19pm (UTC)")
        XCTAssertEqual(q.weekPercent, 28)
        XCTAssertEqual(q.weekResets, "Aug 30, 12:59pm (UTC)")
    }

    /// ⚠️ #117 那条：输出里 `Current week (Fable): 0% used` 也带 `% used`。
    /// 锚点必须是「Current week (all models)」这行**文字**，不是「第几个 %」。
    func test_不能把Fable分项当成周用量() throws {
        XCTAssertEqual(try XCTUnwrap(Quota.parse(real)).weekPercent, 28,
                       "周用量必须是 all models 那个 28%，不是 Fable 的 0%")
    }

    /// 「What's contributing」那一整段全是 `95% of your usage …` 这样的说明文字，
    /// 一条都不许被读成额度。
    func test_不能把正文里的百分比读进来() throws {
        let q = try XCTUnwrap(Quota.parse(real))
        XCTAssertEqual(q.sessionPercent, 11)
        XCTAssertEqual(q.weekPercent, 28)
    }

    /// 档位不在 `/usage` 里（它只说 "using your subscription"），在 credentials 里（#122）。
    func test_读得出订阅档位() throws {
        XCTAssertEqual(try XCTUnwrap(Quota.parse(real)).plan, "Max 20x")
    }

    func test_档位各档都认() {
        XCTAssertEqual(Quota.plan(of: #""rateLimitTier":"default_claude_max_20x""#), "Max 20x")
        XCTAssertEqual(Quota.plan(of: #""rateLimitTier":"default_claude_max_5x""#), "Max 5x")
        XCTAssertEqual(Quota.plan(of: #""rateLimitTier":"claude_pro""#), "Pro")
        // 只有 subscriptionType 时兜底
        XCTAssertEqual(Quota.plan(of: #""subscriptionType":"max""#), "Max")
        XCTAssertEqual(Quota.plan(of: #""subscriptionType":"pro""#), "Pro")
        // 读不到档位不是错误 —— 界面上只是少一个小标签，额度照显示
        XCTAssertEqual(Quota.plan(of: "没有相关字段"), "")
    }

    /// 那台机器上没装 claude → `probeCommand` 里 `command -v` 那步就 `exit 0`，输出是空的。
    /// ⚠️ **返回 nil，界面整块藏掉** —— 绝不能退化成一个 0%（#51）。
    func test_没有额度信息就返回空() {
        XCTAssertNil(Quota.parse(""))
        XCTAssertNil(Quota.parse("bash: claude: command not found"))
        // 登录过期时只有这一句，没有任何百分比
        XCTAssertNil(Quota.parse("You are currently using your subscription to power your Claude Code usage"))
    }

    /// 只解出一半也算没解出来 —— **半个额度比没有额度更误导**。
    func test_缺了周那行也不给半个结果() {
        XCTAssertNil(Quota.parse("Current session: 11% used · resets Aug 28, 5:19pm (UTC)"))
    }

    /// `· resets …` 是可选的：真输出里 `Current week (Fable): 0% used` 就没有。
    /// 百分比是主，重置时间可空 —— 空了就少显示一段，不该整块丢掉。
    func test_没有重置时间也认() throws {
        let q = try XCTUnwrap(Quota.parse("Current session: 5% used\nCurrent week (all models): 8% used"))
        XCTAssertEqual(q.sessionPercent, 5)
        XCTAssertEqual(q.sessionResets, "")
        XCTAssertEqual(q.weekPercent, 8)
        XCTAssertEqual(q.weekResets, "")
    }

    /// ⚠️⚠️ 安全回归（#122）：credentials 里还有 access / refresh token。
    /// 命令**只许 grep 那两个字段**，一旦有人图省事改成 `cat`，token 就会进日志和抓屏。
    func test_取档位绝不整个读credentials() {
        let cmd = Quota.probeCommand
        XCTAssertTrue(cmd.contains("grep -oE"), "档位只能 grep 出来")
        XCTAssertFalse(cmd.contains("cat "), "绝不能整个 cat credentials")
        XCTAssertTrue(cmd.contains("rateLimitTier"))
        XCTAssertTrue(cmd.contains("subscriptionType"))
    }

    /// ⚠️ #117 的三条好处全靠这条命令的形状。改坏了没人会立刻发现 —— 界面只是「查不到」。
    func test_命令不借会话也不进沙箱() {
        let cmd = Quota.probeCommand
        // 非交互打印模式：不往任何 tmux 会话送键
        XCTAssertTrue(cmd.contains("claude -p '/usage'"))
        XCTAssertFalse(cmd.contains("tmux"), "额度不再借会话（#117）")
        // `IS_SANDBOX` 是给交互式 --dangerously-skip-permissions 用的，-p 模式加了可能改行为
        XCTAssertFalse(cmd.contains("IS_SANDBOX"))
        // 非交互 SSH 的 PATH 是残的，claude 是 node 脚本 —— 这两段都不能少
        XCTAssertTrue(cmd.contains("$HOME/.local/bin"))
        XCTAssertTrue(cmd.contains("/opt/node*/bin"))
        // 卡住时别让界面上的「查着…」转到天荒地老
        XCTAssertTrue(cmd.contains("timeout 30"))
    }
}
