package app.yxi.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.window.application
import kotlinx.coroutines.*
import java.awt.Robot
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

class AcpAuthenticationDialogTest {
    @Test fun `embedded authentication terminal accepts keyboard input and reports actual exit`() {
        check(!System.getenv("YXI_ISOLATED_TEST_RUN").isNullOrBlank())
        check(System.getProperty("user.home") == "/sandbox/home")
        System.setProperty("skiko.renderApi", "SOFTWARE")
        val ready = File("/sandbox/tmp/auth-ui-ready")
        val answer = File("/sandbox/tmp/auth-ui-answer")
        val script = File("/sandbox/tmp/auth-ui.py").apply { writeText("""
import os,sys,pathlib
assert os.isatty(0) and os.isatty(1)
print('\033[32m终端认证测试\033[0m', flush=True)
pathlib.Path('${ready.path}').write_text('ready')
value=input('输入 ok 后按 Enter：')
pathlib.Path('${answer.path}').write_text(value)
sys.exit(0 if value == 'ok' else 7)
""".trimIndent()) }
        val plan = AcpTerminalAuthPlan(listOf("/usr/bin/python3", script.path), "/sandbox/home", System.getenv())
        var code: Int? = null
        var problem: String? = null
        var failure: Throwable? = null
        application(exitProcessOnExit = false) {
            YxiTheme { AcpAuthenticationDialog(plan) { exit, error -> code = exit; problem = error; exitApplication() } }
            LaunchedEffect(Unit) {
                try {
                    withTimeout(5000) { while (!ready.isFile) delay(20) }
                    delay(700)
                    val window = java.awt.Window.getWindows().filterIsInstance<java.awt.Dialog>().single { it.isShowing && it.title == "运行器认证" }
                    ImageIO.write(Robot().createScreenCapture(java.awt.Rectangle(window.locationOnScreen, window.size)), "png", File("/results/acp-auth-terminal.png"))
                    withContext(Dispatchers.IO) { Robot().apply {
                        mouseMove(window.locationOnScreen.x + 200, window.locationOnScreen.y + 160)
                        mousePress(InputEvent.BUTTON1_DOWN_MASK); mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                        keyPress(KeyEvent.VK_O); keyRelease(KeyEvent.VK_O)
                        keyPress(KeyEvent.VK_K); keyRelease(KeyEvent.VK_K)
                        keyPress(KeyEvent.VK_ENTER); keyRelease(KeyEvent.VK_ENTER)
                    } }
                    delay(5000)
                    if (code == null) error("认证终端未返回退出结果")
                } catch (e: CancellationException) { throw e }
                catch (e: Throwable) { failure = e; exitApplication() }
            }
        }
        failure?.let { throw it }
        assertNull(problem)
        assertEquals(0, code)
        assertEquals("ok", answer.readText())
    }
}
