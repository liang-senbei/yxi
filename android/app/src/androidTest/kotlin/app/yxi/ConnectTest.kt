package app.yxi

import app.yxi.agent.Connect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「连接」的解析器。样本是 2026-09-02 在服务器上真跑出来的原文。 */
class ConnectTest {

    private val status = """
        __GH__
        github.com
          ✓ Logged in to github.com account liang-senbei (/root/.config/gh/hosts.yml)
          - Active account: true
          - Git operations protocol: https
          - Token: gho_************
        __MCP__
        Checking MCP server health…

        notion: https://mcp.notion.com/mcp (HTTP) - ! Needs authentication
        github: https://api.githubcopilot.com/mcp/ (HTTP) - ✓ Connected
        sentry: https://mcp.sentry.dev/mcp (HTTP) - ✗ Failed to connect
        __CLAUDE__
        {
          "loggedIn": true,
          "authMethod": "claude.ai",
          "apiProvider": "firstParty",
          "email": "someone@example.com",
          "subscriptionType": "max"
        }
        __CODEX__
        Logged in using ChatGPT
        __END__
    """.trimIndent()

    @Test fun 状态_三种都认() {
        val s = Connect.parseStatus(status)
        assertEquals("liang-senbei", s.ghUser)
        assertEquals(Connect.State.NEEDS_AUTH, s.mcp["notion"])
        assertEquals(Connect.State.CONNECTED, s.mcp["github"])
        assertEquals(Connect.State.FAILED, s.mcp["sentry"])
        assertTrue(s.ghInstalled && s.claudeInstalled)
        assertEquals("someone@example.com", s.claudeUser)
        assertTrue(s.codexInstalled && s.codexLogged && s.tmuxInstalled)
    }

    @Test fun 状态_新机器_什么都没装() {
        val s = Connect.parseStatus("__GH__\nNO_GH\n__MCP__\nNO_CLAUDE\n__CLAUDE__\nNO_CLAUDE\n__CODEX__\nNO_CODEX\n__TMUX__\nNO_TMUX\n__END__\n")
        assertTrue(!s.claudeInstalled && !s.codexInstalled && !s.tmuxInstalled)
        assertNull(s.claudeUser)
        assertEquals(Connect.State.ABSENT, s.of(Connect.CATALOG.first { it.key == "claude" }))
        assertTrue(!s.installed(Connect.CATALOG.first { it.key == "codex" }))
    }

    @Test fun 状态_装了没登录() {
        val s = Connect.parseStatus(
            "__GH__\nNO_GH\n__MCP__\n__CLAUDE__\n{\"loggedIn\": false, \"authMethod\": \"none\"}\n__CODEX__\nNot logged in\n__TMUX__\n__END__\n"
        )
        assertTrue(s.claudeInstalled && s.codexInstalled && s.tmuxInstalled)
        assertNull(s.claudeUser)
        assertTrue(!s.codexLogged)
    }

    /** 2026-09-02 服务器上 `codex login --device-auth` 的原文 */
    @Test fun codex_一次性码_在下一行() {
        val pane = """
            Follow these steps to sign in with ChatGPT using device code authorization:
            1. Open this link in your browser and sign in to your account
               https://auth.openai.com/codex/device
            2. Enter this one-time code (expires in 15 minutes)
               QUUK-AW27Q
            Continue only if you started this login in Codex.
        """.trimIndent()
        assertEquals("QUUK-AW27Q", Connect.codexCode(pane))
        assertNull(Connect.codexCode("Welcome to Codex"))
    }

    /** 2026-09-02 `claude auth login` 无浏览器时的原文（域名已经从 claude.ai 换成 claude.com/cai） */
    @Test fun claude_登录地址() {
        val pane = "Opening browser to sign in…\nIf the browser didn't open, visit: https://claude.com/cai/oauth/authorize?code=true&client_id=9d1c&response_type=code&redirect_uri=https%3A%2F%2Fplatform.claude.com%2Foauth%2Fcode%2Fcallback&scope=user%3Aprofile\nPaste code here if prompted > "
        assertEquals(
            "https://claude.com/cai/oauth/authorize?code=true&client_id=9d1c&response_type=code&redirect_uri=https%3A%2F%2Fplatform.claude.com%2Foauth%2Fcode%2Fcallback&scope=user%3Aprofile",
            Connect.claudeUrl(pane),
        )
        assertNull(Connect.claudeUrl("Paste code here if prompted >"))
    }

    @Test fun 状态_没装的机器() {
        val s = Connect.parseStatus("__GH__\nNO_GH\n__MCP__\nNO_CLAUDE\n__END__\n")
        assertNull(s.ghUser)
        assertTrue(!s.ghInstalled && !s.claudeInstalled && s.mcp.isEmpty())
    }

    @Test fun gh的码和两个提示() {
        val pane = "? Authenticate Git with your GitHub credentials? (Y/n)"
        assertTrue(Connect.ghAsksGit(pane)); assertNull(Connect.ghCode(pane))
        val p2 = "! First copy your one-time code: 0468-EECD\nPress Enter to open https://github.com/login/device in your browser..."
        assertEquals("0468-EECD", Connect.ghCode(p2)); assertTrue(Connect.ghAsksOpen(p2)); assertTrue(!Connect.ghAsksGit(p2))
    }

    @Test fun mcp授权地址和回调端口() {
        val pane = "Visit this URL to authorize:\n  https://mcp.notion.com/authorize?response_type=code&client_id=x&" +
            "redirect_uri=http%3A%2F%2Flocalhost%3A64202%2Fcallback&state=abc\nWaiting for authorization… (^C to cancel)"
        val u = Connect.loginUrl(pane)!!
        assertTrue(u.startsWith("https://mcp.notion.com/authorize?"))
        assertEquals(64202, Connect.callbackPort(u))
        assertNull(Connect.parseDone(pane))
        assertEquals(false, Connect.parseDone(pane + "\nCouldn't complete authentication for \"notion\": Invalid authorization code format\n__DONE__1"))
        assertEquals(true, Connect.parseDone("Authenticated\n__DONE__0"))
    }

    /** 文本和回车必须分两条、中间 sleep（#174） */
    @Test fun 粘回调地址_分两条送() {
        val c = Connect.pasteCommand("notion", "http://localhost:64202/callback?code=a'b")
        assertTrue(c.contains("-l 'http://localhost:64202/callback?code=a'\\''b'"))
        assertTrue(c.contains("sleep 0.4") && c.endsWith("Enter"))
    }
}
