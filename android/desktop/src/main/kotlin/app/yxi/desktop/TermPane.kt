package app.yxi.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.unit.dp
import app.yxi.agent.Session
import app.yxi.ssh.SshSession
import com.jediterm.terminal.Questioner
import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.TextStyle
import com.jediterm.terminal.TtyConnector
import com.jediterm.core.util.TermSize
import com.jediterm.terminal.ui.JediTermWidget
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.awt.Font

/*
 * 终端面板 = **真终端**（老板 09-12：「切换为终端模式就不要有下面的输入框，像服务器本地的终端，
 * 或者像 VSCode 的 remote」）：PTY 直连 `tmux attach`，全色彩、全键盘直通、窗口随拖随变，没有输入框。
 *
 * 组成：JetBrains 的 JediTerm（IntelliJ 终端同源的仿真器 + Swing 控件，core/ui 两个 jar 都在
 * JetBrains 的 intellij-dependencies 仓库，settings.gradle.kts 里加了那个仓）用 SwingPanel 嵌进 Compose；
 * 字节桥接是 [TmuxTtyConnector]，底层走 core 的 [SshSession.openPtyCommand]——手机端踩平的坑
 * （写入顺序、ioLock、resize 合法值、stderr 泵、xterm-256color）全都在 Shell 里。
 *
 * 旧实现是「每秒 tmux capture-pane 抓屏 + 输入框 send-keys」——tmux 对客户端是整屏重绘+光标增量刷新，
 * 没有仿真器排不出那个版面（截屏里碎掉的横线就是它），整个被本文件替换。
 *
 * ⚠️ 1.1.x 的教训在这里也适用：Xvfb / uber-jar 的冒烟验不出真终端（精简 runtime、显示层都不同），
 * 改了这里必须真机装一遍点进终端看。
 */
@Composable
fun TermPane(conn: Conn, session: Session) {
    val t = Tokens.current
    var widget by remember(conn.host.id, session.name) { mutableStateOf<JediTermWidget?>(null) }
    var err by remember(conn.host.id, session.name) { mutableStateOf("") }

    // 开 PTY → 建 JediTerm 会话。会话/主机切换时 LaunchedEffect 重启，widget 换新的
    LaunchedEffect(conn.host.id, session.name) {
        if (!conn.ssh.isAlive) { err = "连接断了 —— 接上后重进终端"; return@LaunchedEffect }
        runCatching {
            // attach 不带 -d：和手机/别的客户端共享同一屏；会话没了 -A 兜底新建（在 ~ 里）
            val cmd = "tmux attach -t ${app.yxi.ssh.Shell.q(session.name)} 2>/dev/null || tmux new -A -s ${app.yxi.ssh.Shell.q(session.name)}"
            val shell = conn.ssh.openPtyCommand(cmd, 120, 30)
            val w = JediTermWidget(120, 30, TermSettings())
            w.setTtyConnector(TmuxTtyConnector(shell, session.name))
            w.start()
            widget = w
        }.onFailure {
            err = it.message?.ifBlank { null } ?: "终端起不来"
        }
    }
    // 离开组合（切 tab / 换会话 / 关窗）= 关掉 attach；tmux 里的会话照活，回来重新 attach 就是
    DisposableEffect(conn.host.id, session.name) {
        onDispose { runCatching { widget?.close() } }
    }

    Box(Modifier.fillMaxSize().background(t.surface2)) {
        val w = widget
        when {
            NativeOverlays.active -> Unit
            w != null -> SwingPanel(factory = { w }, modifier = Modifier.fillMaxSize(), update = {})
            err.isNotBlank() -> Text(err, Modifier.align(Alignment.Center).padding(16.dp), color = t.danger)
            else -> Text("正在打开终端…", Modifier.align(Alignment.Center), color = t.textMuted)
        }
    }
}

/** JediTerm 外观：终端永远深底（手机端同一条规矩：ANSI 彩色是按深底配的），等宽字体要带 CJK。 */
internal class TermSettings : DefaultSettingsProvider() {
    private val win = System.getProperty("os.name").startsWith("Windows")

    override fun getTerminalFont(): Font = Font(if (win) "NSimSun" else "Monospaced", Font.PLAIN, 14)
    override fun getTerminalFontSize(): Float = 14f
    // ⚠️ TerminalColor(int) 是「调色板索引」不是 RGB——塞 0xEBE1D9 会在渲染时越界断言炸掉整个 EDT（踩过）。
    //    RGB 要用 (r, g, b) 三参构造。
    override fun getDefaultStyle(): TextStyle = TextStyle(TerminalColor(0xEB, 0xE1, 0xD9), TerminalColor(0x10, 0x0E, 0x0B))
}

/**
 * JediTerm ↔ core [SshSession.Shell] 的字节桥。
 * ⚠️ read 必须**用 UTF-8 把字节解成字符**再交给 JediTerm（照官方 ProcessTtyConnector 的做法：
 * InputStreamReader 挂在流上，跨 read 边界的多字节序列它自己缓冲拼装）。
 * 之前按 latin1 逐字节塞过——中文全成乱码（UTF-8 字节没被组装），就是这条的教训。
 * read 在 JediTerm 自己的读线程上阻塞着被调，Shell.output 阻塞读正好对口。
 */
private class TmuxTtyConnector(
    private val shell: SshSession.Shell,
    private val sessionName: String,
) : TtyConnector {
    // resize 回调来自 JediTerm 的线程；Shell.resize 是 suspend，甩到 IO
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reader = java.io.InputStreamReader(shell.output, Charsets.UTF_8)

    override fun init(questioner: Questioner): Boolean = true
    override fun resize(termSize: TermSize) {
        scope.launch { shell.resize(termSize.columns, termSize.rows) }
    }

    override fun read(buf: CharArray, offset: Int, length: Int): Int {
        val n = reader.read(buf, offset, length)   // 阻塞读：没数据就停在这，JediTerm 不介意
        return if (n < 0) -1 else n
    }

    override fun write(bytes: ByteArray) { scope.launch { shell.write(bytes) } }
    override fun write(string: String) { scope.launch { shell.write(string) } }
    override fun isConnected(): Boolean = shell.isConnected
    override fun close() { shell.close() }   // PTY 断了 tmux 里的会话照活，attach 回去就在
    override fun getName(): String = "tmux:$sessionName"
    override fun waitFor(): Int = 0
    override fun ready(): Boolean = true
}
