package app.yxi.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.yxi.agent.Session
import app.yxi.agent.SessionProbe
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * 终端面板 —— 选的是「每秒抓屏」方案，不是 PTY 直连。
 *
 * 实测（用 pty 挂 `tmux attach` 3.5 秒抓了 7KB）：tmux 对客户端是整屏重绘 + 光标定位的增量刷新，
 * 去掉 ANSI 之后同一屏文字反复出现三遍、增量更新的碎片全糊在一行里 —— 没有终端仿真器根本没法看。
 * 手机端能用是因为接了 ConnectBot 的 termlib 仿真器；桌面这边没有现成的。
 * 所以：每秒 `tmux capture-pane -p`（[SessionProbe.peek]）拿 tmux 自己排好版的整屏，按键走 `tmux send-keys`。
 * 不开 PTY 通道，切 tab / 换会话就没东西要关（每次 exec 自己开关通道，effect 取消即停）。
 *
 * ponytail：没颜色、没鼠标、不改 tmux 窗口尺寸（resize-window 会把 window-size 钉成 manual，
 * 手机 `attach -d` 就缩不回去了）—— 屏幕宽度是上一个 attach 的客户端留下的。要完整终端就换 JediTerm。
 */
@Composable
fun TermPane(conn: Conn, session: Session) {
    var screen by remember(session.name) { mutableStateOf("") }
    var tick by remember { mutableStateOf(0) }   // 按键送达后 +1：马上抓一次屏，不等下一秒
    var input by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    val focus = remember { FocusRequester() }

    LaunchedEffect(conn, session.name, tick) {
        while (true) {
            screen = if (conn.ssh.isAlive) SessionProbe.peek(conn.ssh, session.name, 200).trimEnd() else ""
            delay(1000)
        }
    }
    LaunchedEffect(screen) { scroll.scrollTo(scroll.maxValue) }   // 输入框在屏幕底部，抓到新屏就跟到底
    LaunchedEffect(Unit) { focus.requestFocus() }

    // C-c 不在 SessionProbe.sendKey 的白名单里（那是给选项按键用的），自己拼一条；会话名来自 snapshot，照样 q() 一遍
    fun key(k: String) = scope.launch {
        if (k == "C-c") conn.ssh.exec("tmux send-keys -t ${app.yxi.ssh.Shell.q(session.name)} C-c")
        else SessionProbe.sendKey(conn.ssh, session.name, k)
        tick++
    }

    Column(Modifier.fillMaxSize()) {
        SelectionContainer(
            Modifier.weight(1f).fillMaxWidth().background(Tokens.current.surface1)
                .verticalScroll(scroll).horizontalScroll(rememberScrollState()).padding(8.dp),
        ) {
            Text(
                screen.ifEmpty { if (conn.ssh.isAlive) "（没抓到屏幕：会话可能已经结束）" else "（连接断了）" },
                fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Tokens.current.textPrimary, softWrap = false,
            )
        }
        OutlinedTextField(
            value = input, onValueChange = { input = it }, singleLine = true,
            placeholder = { Text("Enter 发送 · Esc / Ctrl+C / ↑ ↓ 直通到会话") },
            modifier = Modifier.fillMaxWidth().padding(8.dp).focusRequester(focus).onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    e.key == Key.Enter -> {
                        val t = input; input = ""
                        // 空着按 Enter = 单送回车（确认提示用）；有字走 send（文本和回车隔 0.4 秒，多行才发得出去）
                        scope.launch {
                            if (t.isEmpty()) SessionProbe.sendKey(conn.ssh, session.name, "Enter") else SessionProbe.send(conn.ssh, session.name, t)
                            tick++
                        }
                        true
                    }
                    e.key == Key.Escape -> { key("Escape"); true }
                    e.isCtrlPressed && e.key == Key.C -> { key("C-c"); true }
                    e.key == Key.DirectionUp -> { key("Up"); true }
                    e.key == Key.DirectionDown -> { key("Down"); true }
                    else -> false
                }
            },
        )
    }
}
