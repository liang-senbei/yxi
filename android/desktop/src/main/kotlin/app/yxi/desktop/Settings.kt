package app.yxi.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 设置弹窗（Ctrl+, / 托盘「设置…」）。分组照 Codex：常规 / 外观 / 通知；账户以后再说。值都进 Store.pref，读的地方自己重组。 */
@Composable
fun SettingsDialog(state: AppState) {
    var autostart by remember { mutableStateOf(autostartEnabled()) }
    AlertDialog(
        onDismissRequest = { state.showSettings = false },
        confirmButton = { TextButton({ state.showSettings = false }) { Text("完成") } },
        title = { Text("设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Section("常规")
                SwitchRow("关闭窗口时留在托盘", Store.pref("closeToTray", "1") == "1") { Store.setPref("closeToTray", if (it) "1" else "0") }
                SwitchRow("开机自启", autostart, enabled = isWindows) { autostart = it; setAutostart(it) }
                SwitchRow("启动时重连上次主机", Store.pref("reconnectOnStart", "1") == "1") { Store.setPref("reconnectOnStart", if (it) "1" else "0") }
                Section("外观")
                Choice("主题", listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色"), Store.pref("theme", "system")) { Store.setPref("theme", it) }
                Section("通知")
                Choice("轮次完成 / 需要你处理", listOf("always" to "始终", "unfocused" to "仅在未聚焦时", "never" to "从不"), Store.pref("notify", "unfocused")) { Store.setPref("notify", it) }
                // 版本号（老板 09-12：设置里要有）。`java -jar` 跑没有 jpackage 属性，显示「开发版」
                Text(
                    "Yxi " + (Updater.version.takeIf { it != "dev" }?.let { "v$it" } ?: "开发版"),
                    Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, color = Tokens.current.textMuted,
                )
            }
        },
    )
}

@Composable
private fun Section(title: String) = Text(title, Modifier.padding(top = 8.dp), color = Tokens.current.textMuted, style = MaterialTheme.typography.labelMedium)

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = if (enabled) Tokens.current.textPrimary else Tokens.current.textMuted)
        Switch(checked, onChange, enabled = enabled)
    }
}

/** 一行单选：标签 + 几个 RadioButton（选项 = 存的值 to 显示名）。 */
@Composable
private fun Choice(label: String, options: List<Pair<String, String>>, value: String, onSelect: (String) -> Unit) {
    Column {
        Text(label)
        Row(verticalAlignment = Alignment.CenterVertically) {
            options.forEach { (v, name) ->
                Row(Modifier.clickable { onSelect(v) }.padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = v == value, onClick = { onSelect(v) })
                    Text(name, color = Tokens.current.textSecondary)
                }
            }
        }
    }
}

private val isWindows = System.getProperty("os.name").startsWith("Windows")
private const val RUN = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
private fun reg(vararg args: String): Int = runCatching { ProcessBuilder("reg", *args).redirectErrorStream(true).start().run { inputStream.readAllBytes(); waitFor() } }.getOrDefault(-1)

/** 开机自启 = HKCU\…\Run 下有没有 Yxi 这条（reg query 退出码 0），不另存偏好——用户在任务管理器里关掉了也能如实显示。非 Windows 一律关且灰掉。 */
private fun autostartEnabled() = isWindows && reg("query", RUN, "/v", "Yxi") == 0

/**
 * 开机自启那条注册表值的 `.reg` 正文。**单独抽出来是为了能在 Linux 上测** —— 不然验一次要装 Windows。
 *
 * ⚠️ 为什么不直接 `reg add … /d "<路径>"`（原来的写法，审查 P2 点的就是它）：
 *    · 值里**必须带引号** —— `C:\Users\Li Ming\AppData\Local\Yxi\Yxi.exe` 不带引号的话，
 *      Windows 起动时会先去试 `C:\Users\Li.exe`，用户名带空格的机器上开机自启直接不工作。
 *    · 而这对引号要穿过 **Java 的 ProcessBuilder**：它在 Windows 上自己有一套加引号 / 转义的规则，
 *      「参数本身已经带引号」时到底原样传还是再转义一层，没有明确保证 —— 落进注册表的可能是
 *      没引号、也可能是 `\"…\"`，两种都是坏的，而且**只有在真 Windows 上才看得出来**。
 *    `.reg` 文件的转义是有定义的（`\` → `\\`、`"` → `\"`），我们逐字写出来再 `reg import`，不经过任何猜测。
 * ⚠️ 文件要写 **UTF-16LE + BOM**：用户名是中文的机器很常见，ANSI 存进去会变成乱码路径。
 */
internal fun autostartReg(exe: String): String {
    val esc = exe.replace("\\", "\\\\").replace("\"", "\\\"")
    return "Windows Registry Editor Version 5.00\r\n\r\n" +
        "[HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Run]\r\n" +
        "\"Yxi\"=\"\\\"" + esc + "\\\"\"\r\n"
}

/** 值 = 当前可执行文件：jpackage 装的就是 %LOCALAPPDATA%\Yxi\Yxi.exe（java -jar 开发时是 java.exe，无所谓）。 */
private fun setAutostart(on: Boolean) {
    if (!on) { reg("delete", RUN, "/v", "Yxi", "/f"); return }
    val exe = ProcessHandle.current().info().command().orElse("")
    if (exe.isBlank()) return
    runCatching {
        val f = java.io.File.createTempFile("yxi-autostart", ".reg")
        f.writeBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + autostartReg(exe).toByteArray(Charsets.UTF_16LE))
        reg("import", f.absolutePath)
        f.delete()
    }
}
