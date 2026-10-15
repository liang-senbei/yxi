package app.yxi.desktop

import app.yxi.agent.ClaudeSessions
import app.yxi.agent.PermissionMode
import app.yxi.agent.Session
import app.yxi.agent.SessionState
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M4s：「在 Yxi 里继续」的启动计划（`claude --resume`）和侧栏 / 只读页用的那几个小函数。不碰 Store、不连主机。
 */
class ExternalClaudeTest {
    private val id = "5f1c2d3e-4a5b-4c6d-8e7f-001122334455"
    private val host = Host("h1", "示例", "build.example.invalid", username = "dev")

    @Test fun `resume plan passes the session id and does not create the directory`() {
        val plan = DesktopLaunchPlan("/srv/web", "claude", DesktopLaunchPlan.newRequestId(), permissionMode = PermissionMode.Manual, resumeSessionId = id)
        val command = plan.command()
        assertTrue(command.contains("'--resume' '$id' '--permission-mode' 'manual'"), command)
        assertTrue(command.contains("[ -d \"\$d\" ] || { echo"), "目录不在就报 nodir")
        assertFalse(command.contains("mkdir -p"), "接着跑不该凭空建出原来的目录")
        assertTrue(plan.sessionName.startsWith("cc-web-"))
        // 不带 resume 的新会话照旧
        val fresh = DesktopLaunchPlan("/srv/web", "claude", DesktopLaunchPlan.newRequestId())
        assertFalse(fresh.command().contains("--resume")); assertTrue(fresh.command().contains("mkdir -p"))
    }

    @Test fun `resume plan re-checks the session on the server right before tmux starts`() {
        val command = DesktopLaunchPlan("/srv/web", "claude", DesktopLaunchPlan.newRequestId(), resumeSessionId = id).command()
        val guard = command.indexOf(" guard '$id' \"\$want\"")
        assertTrue(guard > 0, "没有 guard")
        assertTrue(command.indexOf("tmux has-session") < guard && guard < command.indexOf("tmux new-session"), "guard 要在复用检查之后、起 tmux 之前")
        assertTrue(command.contains("[ \"\$guard\" = ok ] || { echo \"${app.yxi.agent.Dirs.TAG}:resume-\${guard:-unknown}\"; exit 0; }"), "核不过就不起、回原因")
        assertTrue(command.contains("case \"\$want\" in *'#'*)"), "解析符号链接后的目录也不许有 #")
        assertFalse(DesktopLaunchPlan("/srv/web", "claude", DesktopLaunchPlan.newRequestId()).command().contains(" guard "), "新会话不跑 guard")
    }

    @Test fun `resume plan rejects anything that is not a plain Claude session id`() {
        val request = DesktopLaunchPlan.newRequestId()
        listOf("x; rm -rf ~", "5F1C2D3E-4A5B-4C6D-8E7F-001122334455", "$id ", "--help", "").forEach { bad ->
            assertFailsWith<IllegalArgumentException> { DesktopLaunchPlan("/srv/web", "claude", request, resumeSessionId = bad) }
        }
        assertFailsWith<IllegalArgumentException> { DesktopLaunchPlan("/srv/web", "codex", request, resumeSessionId = id) }
        assertFailsWith<IllegalArgumentException> { DesktopLaunchPlan("/srv/web", "claude", request, initialPrompt = "继续", resumeSessionId = id) }
        assertFailsWith<IllegalArgumentException> { DesktopLaunchPlan("/srv/web", "claude", request, isolatedWorktree = true, resumeSessionId = id) }
        // tmux 会把 -c 的目录当格式展开：#(命令) 会被执行
        assertFailsWith<IllegalArgumentException> { DesktopLaunchPlan("/srv/#(touch x)", "claude", request, resumeSessionId = id) }
    }

    @Test fun `rows hide sessions Yxi lists and remember adopted ones`() {
        val other = "6a2b3c4d-5e6f-4a7b-8c9d-112233445566"
        val conn = Conn(host, NoHostKeys)
        try {
            assertTrue(ExternalClaude.rows(conn).isEmpty(), "还没读过是空的")
            conn.sessions = listOf(Session("cc-api", 1, false, "/srv/api", 0, SessionState.Idle, "", 0.0, cmd = "claude", runtimeId = "r1"))
            conn.claudeSessions = ClaudeSessions.Snapshot(
                listOf(ClaudeSessions.Entry(id, "/p/$id.jsonl", "/srv/web", "网页", 20), ClaudeSessions.Entry(other, "/p/$other.jsonl", "/srv/api", "接口", 10)),
                listOf(ClaudeSessions.Process(7, other, live = true, tmux = "cc-api")))
            assertEquals(listOf(id), ExternalClaude.rows(conn).map { it.entry.id })
            assertEquals("cc-api", ExternalClaude.managedSession(conn, other)?.name, "登记里在 Yxi 的 tmux 会话里跑着：只读页给「打开那个会话」")
            assertNull(ExternalClaude.managedSession(conn, id))
            // 刚在 Yxi 里接着跑：新进程的登记还没写出来，tmux 会话一出现就从列表里去掉
            conn.claudeAdopted[id] = "cc-web-abc"
            assertEquals(listOf(id), ExternalClaude.rows(conn).map { it.entry.id }, "tmux 会话还没出现时照旧列着")
            conn.sessions = conn.sessions + Session("cc-web-abc", 1, false, "/srv/web", 0, SessionState.Idle, "", 0.0, cmd = "claude", runtimeId = "r2")
            assertTrue(ExternalClaude.rows(conn).isEmpty())
            assertEquals("cc-web-abc", ExternalClaude.managedSession(conn, id)?.name)
        } finally { conn.close() }
    }

    @Test fun `message count decides whether earlier history may exist`() {
        fun line(type: String) = JSONObject().put("type", type).put("uuid", "u").toString()
        assertEquals(3, ExternalClaude.messages(listOf(line("user"), line("assistant"), line("queue-operation"), line("user"), "{broken", line("mode"))))
    }

    @Test fun `untitled sessions show their folder`() {
        assertEquals("修登录页", ExternalClaude.title(ClaudeSessions.Entry(id, "/p", "/srv/web", "修登录页", 0)))
        assertEquals("web · 没有标题", ExternalClaude.title(ClaudeSessions.Entry(id, "/p", "/srv/web/", "", 0)))
        assertEquals("/ · 没有标题", ExternalClaude.title(ClaudeSessions.Entry(id, "/p", "/", "", 0)))
    }

    @Test fun `retries reuse one launch request per session`() {
        val first = ExternalClaude.requestId(host, id)
        assertEquals(first, ExternalClaude.requestId(host, id))
        ExternalClaude.forgetRequest(host, id)
        assertTrue(first != ExternalClaude.requestId(host, id))
        ExternalClaude.forgetRequest(host, id)
    }
}
