package app.yxi.term

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.yxi.ssh.HostConfig
import app.yxi.ssh.SshSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ⚠️ 临时界面：G2 的验证载体，只证明「连得上 + PTY 出得来 + 打得进去」。
 * 真终端界面等控件选型定了再做，届时本文件与 [AnsiText] 一起删掉。
 */
@Composable
fun G2Screen(cfg: HostConfig, attachTo: String?, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("未连接") }
    val buf = remember { StringBuilder() }
    var screen by remember { mutableStateOf("") }
    var typed by remember { mutableStateOf("") }
    var shell by remember { mutableStateOf<SshSession.Shell?>(null) }
    val scroll = rememberScrollState()

    LaunchedEffect(Unit) {
        val s = SshSession(cfg)
        runCatching {
            status = "连接中…"
            s.connect()
            status = "已连接，开 shell…"
            val sh = s.openShell(80, 24)
            shell = sh
            status = "PTY 已开 · ${cfg.username}@${cfg.hostname}:${cfg.port}"
            if (attachTo != null) sh.write("tmux attach -t $attachTo || tmux new -s $attachTo\n")
            withContext(Dispatchers.IO) {
                val b = ByteArray(8192)
                while (true) {
                    val n = sh.output.read(b)
                    if (n < 0) break
                    buf.append(AnsiText.strip(String(b, 0, n)))
                    if (buf.length > 20_000) buf.delete(0, buf.length - 20_000)
                    screen = buf.toString()
                }
            }
        }.onFailure { status = "失败：${it::class.simpleName}: ${it.message}" }
    }
    LaunchedEffect(screen) { runCatching { scroll.animateScrollTo(scroll.maxValue) } }

    Column(modifier.fillMaxSize().padding(14.dp)) {
        Text(
            status,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.tertiary,
        )
        Spacer(Modifier.height(10.dp))
        Surface(
            Modifier.weight(1f).fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            shape = MaterialTheme.shapes.medium,
        ) {
            Text(
                screen,
                Modifier.verticalScroll(scroll).padding(12.dp),
                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                Modifier.weight(1f),
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.small,
            ) {
                BasicTextField(
                    typed,
                    { typed = it },
                    Modifier.padding(14.dp).fillMaxWidth(),
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                )
            }
            Spacer(Modifier.width(8.dp))
            Button(
                { scope.launch { shell?.write(typed + "\n"); typed = "" } },
                enabled = shell != null,
            ) { Text("发送") }
        }
    }
}
