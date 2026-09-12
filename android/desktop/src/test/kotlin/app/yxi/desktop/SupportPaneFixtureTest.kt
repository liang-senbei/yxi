package app.yxi.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import app.yxi.agent.SupportApi
import org.json.JSONObject
import org.json.JSONArray
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.*

class SupportPaneFixtureTest {
    @Test fun `support page submits only explicit test content and preserves receipts`() {
        val directory = System.getenv("YXI_SUPPORT_UI_FIXTURE")
        assumeTrue(directory != null)
        val output = File(directory!!)
        val workspace = SupportWorkspace(output.resolve("drafts.json"))
        val ticket = JSONObject("""{"id":"7","category":"bug","text":"窗口缩放后，右侧预览有一点拥挤。","status":"closed","createdAt":"2026-09-13 09:00","updatedAt":"2026-09-13 10:00","unread":true,"replies":[{"by":"official","text":"请补充窗口尺寸与操作步骤，我们会继续检查。","at":"2026-09-13 10:00"}]}""")
        val root = JSONObject().put("items", JSONArray().put(ticket)).put("nextCursor", JSONObject.NULL).put("unread", 1)
        var creates = 0; var replies = 0
        val api = SupportApi { path, method, body ->
            output.resolve("actions.txt").appendText("$method $path ${body.orEmpty()}\n")
            200 to when {
                method == "GET" -> root.toString()
                path.endsWith("/read") -> { ticket.put("unread", false); root.put("unread", 0); """{"ok":true,"unread":0}""" }
                path.endsWith("/reply") -> {
                    replies++; val content = JSONObject(body!!).getString("text")
                    assertEquals("REPLY_FROM_DESKTOP", content)
                    ticket.put("status", "open").getJSONArray("replies").put(JSONObject().put("by", "user").put("text", content).put("at", "now"))
                    ticket.toString()
                }
                path == "/api/support/tickets" -> {
                    creates++; val content = JSONObject(body!!)
                    assertEquals("NEW_FROM_DESKTOP", content.getString("text"))
                    assertEquals("bug", content.getString("category"))
                    assertEquals(setOf("category", "text", "version", "device"), content.keys().asSequence().toSet())
                    val created = JSONObject().put("id", "8").put("category", "bug").put("text", content.getString("text")).put("status", "open")
                        .put("createdAt", "now").put("updatedAt", "now").put("unread", false).put("replies", JSONArray())
                    root.put("items", JSONArray().put(created).put(ticket))
                    """{"id":"8","createdAt":"now"}"""
                }
                else -> error("Unexpected support fixture endpoint")
            }
        }
        application(exitProcessOnExit = false) {
            Window(onCloseRequest = ::exitApplication, title = "Yxi support fixture", state = rememberWindowState(width = 1100.dp, height = 800.dp)) {
                YxiTheme { SupportPane("fixture-user", api, workspace, onBack = ::exitApplication, onUnread = {}) }
            }
        }
        assertEquals(1, creates); assertEquals(1, replies)
        assertTrue(workspace.running.isEmpty())
        assertEquals(2, SupportDrafts(output.resolve("drafts.json")).entries.count { it.state == SupportSendState.Confirmed })
        assertEquals("open", ticket.getString("status"))
    }
}
