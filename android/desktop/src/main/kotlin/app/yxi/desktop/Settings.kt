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
                Section("外观")
                Choice("主题", listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色"), Store.pref("theme", "system")) { Store.setPref("theme", it) }
                Section("通知")
                Choice("轮次完成 / 需要你处理", listOf("always" to "始终", "unfocused" to "仅在未聚焦时", "never" to "从不"), Store.pref("notify", "unfocused")) { Store.setPref("notify", it) }
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

/** 值 = 当前可执行文件：jpackage 装的就是 %LOCALAPPDATA%\Yxi\Yxi.exe（java -jar 开发时是 java.exe，无所谓）。带引号，路径有空格也能起。 */
private fun setAutostart(on: Boolean) {
    if (on) reg("add", RUN, "/v", "Yxi", "/t", "REG_SZ", "/d", "\"${ProcessHandle.current().info().command().orElse("")}\"", "/f")
    else reg("delete", RUN, "/v", "Yxi", "/f")
}
