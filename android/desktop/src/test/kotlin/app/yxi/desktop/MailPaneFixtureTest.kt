package app.yxi.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.yxi.agent.MailApi
import org.json.JSONObject
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.*

/** Runs only in an isolated display; every operation stays in this fake service. */
class MailPaneFixtureTest {
    @Test fun `mail view edits only the simulated mailbox`() {
        val output = System.getenv("YXI_MAIL_UI_FIXTURE")
        assumeTrue(output != null)
        val log = File(output!!, "actions.txt")
        val root = JSONObject("""{"items":[{"id":"101","kind":"system","title":"欢迎来到云曦工作台","body":"你的桌面工作空间已经准备好。\n\n在这里管理服务器、查看网页与文件，并把每一个想法变成清晰的下一步。","from":{"name":"云曦团队"},"createdAt":"2026-09-13 09:00 +08:00","readAt":null,"claimedAt":null,"attachments":[{"kind":"tickets","amount":3,"name":"体验礼物"}]},{"id":"102","kind":"notice","title":"让界面留一点呼吸感","body":"更清晰的层次，更舒服的间距。","createdAt":"2026-09-12 18:00 +08:00","readAt":null,"attachments":[]}],"nextCursor":null,"unread":2,"unclaimed":1}""")
        var claims = 0; var deletes = 0
        val api = MailApi { path, method, body ->
            log.appendText("$method $path ${body.orEmpty()}\n")
            val first = root.getJSONArray("items").optJSONObject(0)
            200 to when {
                method == "GET" -> root.toString()
                path.endsWith("/read") -> { first?.put("readAt", "2026-09-13"); root.put("unread", 1); """{"ok":true,"unread":1}""" }
                path.endsWith("/claim") -> { claims++; first?.put("claimedAt", "2026-09-13"); root.put("unclaimed", 0); """{"replay":false,"tickets":3,"unclaimed":0}""" }
                path.endsWith("/delete") -> { deletes++; root.getJSONArray("items").remove(0); """{"ok":true,"unread":1,"unclaimed":0}""" }
                else -> error("Unexpected fixture operation")
            }
        }
        application(exitProcessOnExit = false) {
            Window(onCloseRequest = ::exitApplication, title = "Yxi mail fixture", state = rememberWindowState(width = 1100.dp, height = 760.dp)) {
                YxiTheme { MailPane("test-user", api, onBack = ::exitApplication, onCounters = {}) }
            }
        }
        assertEquals(1, claims)
        assertEquals(1, deletes)
        assertEquals("102", root.getJSONArray("items").getJSONObject(0).getString("id"))
    }
}
