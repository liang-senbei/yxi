import XCTest
@testable import YxiKit

/// 「连接」的解析器。样本是 2026-09-02 在服务器上真跑出来的原文（跟安卓 ConnectTest 同一份）。
final class ConnectTests: XCTestCase {

    let status = """
    __GH__
    github.com
      ✓ Logged in to github.com account liang-senbei (/root/.config/gh/hosts.yml)
      - Active account: true
    __MCP__
    Checking MCP server health…

    notion: https://mcp.notion.com/mcp (HTTP) - ! Needs authentication
    github: https://api.githubcopilot.com/mcp/ (HTTP) - ✓ Connected
    sentry: https://mcp.sentry.dev/mcp (HTTP) - ✗ Failed to connect
    __END__
    """

    func test_状态三种都认() {
        let s = Connect.parseStatus(status)
        XCTAssertEqual(s.ghUser, "liang-senbei")
        XCTAssertEqual(s.mcp["notion"], .needsAuth)
        XCTAssertEqual(s.mcp["github"], .connected)
        XCTAssertEqual(s.mcp["sentry"], .failed)
        XCTAssertTrue(s.ghInstalled && s.claudeInstalled)
    }

    func test_没装的机器() {
        let s = Connect.parseStatus("__GH__\nNO_GH\n__MCP__\nNO_CLAUDE\n__END__\n")
        XCTAssertNil(s.ghUser)
        XCTAssertFalse(s.ghInstalled); XCTAssertFalse(s.claudeInstalled); XCTAssertTrue(s.mcp.isEmpty)
    }

    func test_gh的码和两个提示() {
        XCTAssertTrue(Connect.ghAsksGit("? Authenticate Git with your GitHub credentials? (Y/n)"))
        let p = "! First copy your one-time code: 0468-EECD\nPress Enter to open https://github.com/login/device in your browser..."
        XCTAssertEqual(Connect.ghCode(p), "0468-EECD")
        XCTAssertTrue(Connect.ghAsksOpen(p)); XCTAssertFalse(Connect.ghAsksGit(p))
    }

    func test_mcp授权地址和结束标记() {
        let pane = "Visit this URL to authorize:\n  https://mcp.notion.com/authorize?response_type=code&client_id=x&"
            + "redirect_uri=http%3A%2F%2Flocalhost%3A64202%2Fcallback&state=abc\nWaiting for authorization… (^C to cancel)"
        let u = Connect.loginURL(pane)!
        XCTAssertTrue(u.hasPrefix("https://mcp.notion.com/authorize?"))
        XCTAssertEqual(Connect.callbackPort(u), 64202)
        XCTAssertNil(Connect.parseDone(pane))
        XCTAssertEqual(Connect.parseDone(pane + "\nCouldn't complete authentication for \"notion\": bad\n__DONE__1"), false)
        XCTAssertEqual(Connect.parseDone("Authenticated\n__DONE__0"), true)
    }

    func test_粘回调地址分两条送() {
        let c = Connect.pasteCommand(key: "notion", redirectURL: "http://localhost:64202/callback?code=a'b")
        XCTAssertTrue(c.contains("-l 'http://localhost:64202/callback?code=a'\\''b'"))
        XCTAssertTrue(c.contains("sleep 0.4") && c.hasSuffix("Enter"))
    }
}
