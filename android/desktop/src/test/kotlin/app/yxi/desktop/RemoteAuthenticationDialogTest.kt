package app.yxi.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.window.application
import kotlinx.coroutines.*
import org.json.JSONObject
import java.awt.Robot
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

class RemoteAuthenticationDialogTest {
    @Test fun `server authentication window accepts keyboard input through its SSH PTY`() = runDialog(false)
    @Test fun `closing server authentication cancels without closing SSH`() = runDialog(true)
    private fun runDialog(cancel: Boolean) {
        check(!System.getenv("YXI_ISOLATED_TEST_RUN").isNullOrBlank())
        check(System.getProperty("user.home") == "/sandbox/home")
        System.setProperty("skiko.renderApi", "SOFTWARE")
        val root = File("/sandbox/tmp/remote-auth-ui-${if (cancel) "cancel" else "success"}").apply { mkdirs() }
        val home = File(root, "home").apply { mkdirs() }
        val ready = File(root, "ready"); val answer = File(root, "answer")
        val bin = File(home, ".local/bin").apply { mkdirs() }
        File(bin, "hermes").apply {
            writeText("""#!/usr/bin/python3
import os,sys,pathlib
assert os.isatty(0) and os.isatty(1)
assert sys.argv[1:] == ['acp','--setup']
assert os.environ['TERM'] == 'xterm-256color'
assert os.environ['AUTH_FIXTURE'] == 'private-fixture-value'
print('服务器认证测试：输入 ok 后按 Enter',flush=True)
pathlib.Path('${ready.path}').write_text(str(os.getpid()))
value=input()
pathlib.Path('${answer.path}').write_text(value)
sys.exit(0 if value == 'ok' else 7)
""")
            setExecutable(true)
        }
        IsolatedSshBridge(File(root, "ssh"), mapOf("HOME" to home.path), File(root, "unused.sock")).use { bridge ->
            runBlocking { bridge.conn.ssh.connect() }
            val method = JSONObject("""{"type":"terminal","args":["--setup"],"env":{"AUTH_FIXTURE":"private-fixture-value"}}""")
            val plan = RemoteAuthenticationPlan(bridge.conn.ssh, "hermes", home.path, method)
            val title = "isolated-bridge · Hermes 认证"
            var code: Int? = null; var problem: String? = null; var failure: Throwable? = null
            try {
                application(exitProcessOnExit = false) {
                    YxiTheme { AuthenticationTerminalDialog(plan, title, { RemoteAuthenticationTerminal.start(plan) }) { exit, error ->
                        code = exit; problem = error; exitApplication()
                    } }
                    LaunchedEffect(Unit) {
                        try {
                            withTimeout(5000) { while (!ready.exists()) delay(20) }
                            delay(700)
                            val dialog = java.awt.Window.getWindows().filterIsInstance<java.awt.Dialog>().single { it.isShowing && it.title == title }
                            ImageIO.write(Robot().createScreenCapture(java.awt.Rectangle(dialog.locationOnScreen, dialog.size)), "png", File("/results/remote-auth-terminal.png"))
                            if (cancel) dialog.dispatchEvent(java.awt.event.WindowEvent(dialog, java.awt.event.WindowEvent.WINDOW_CLOSING))
                            else withContext(Dispatchers.IO) { Robot().apply {
                                mouseMove(dialog.locationOnScreen.x + 200, dialog.locationOnScreen.y + 160)
                                mousePress(InputEvent.BUTTON1_DOWN_MASK); mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                                keyPress(KeyEvent.VK_O); keyRelease(KeyEvent.VK_O); keyPress(KeyEvent.VK_K); keyRelease(KeyEvent.VK_K)
                                keyPress(KeyEvent.VK_ENTER); keyRelease(KeyEvent.VK_ENTER)
                            } }
                            delay(5000); if (code == null && problem == null) error("远端认证未返回退出结果")
                        } catch (e: CancellationException) { throw e }
                        catch (e: Throwable) { failure = e; exitApplication() }
                    }
                }
            } finally { plan.close() }
            failure?.let { throw it }
            if (cancel) {
                assertNull(code); assertEquals("认证已取消", problem); assertFalse(answer.exists())
                val pid = ready.readText().toLong()
                runBlocking { withTimeout(5000) { while (ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) delay(20) } }
            } else { assertNull(problem); assertEquals(0, code); assertEquals("ok", answer.readText()) }
            assertEquals("alive", runBlocking { bridge.conn.ssh.exec("printf alive").trim() })
        }
    }
}
