package app.yxi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.yxi.agent.Dirs
import app.yxi.agent.Model
import app.yxi.agent.TempSessions
import app.yxi.ssh.SshSession
import kotlinx.coroutines.launch

/**
 * 「发起临时会话」：选模型（默认 sonnet）、思考强度（默认 medium）→ 在服务器 /tmp 里起一个会话进去。
 * 开成了立刻跳进对话页。生命周期见 [TempSessions]。
 */
private val EFFORTS = listOf("low", "medium", "high", "xhigh", "max")

@Composable
fun TempSessionDialog(ssh: SshSession?, hostId: String, onOpen: (name: String, cwd: String) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // 默认 sonnet（Claude Code 的 sonnet 别名 = 最新的 Sonnet）+ 中等思考。
    // ⚠️ **线路里设了就跟线路**：settings.json 的 model / effortLevel 正是线路写的那两个键，
    //    读到就拿来当默认，没设才用这里的常量（用户 2026-09-05：「这个配置也要在线路里面改，不然就是默认」）。
    // ⚠️ 但这两个键**不只线路会写** —— `/model` / `/effort` 也把它们写成账号默认（见 Model.kt 实测注释），
    //    所以界面上只说「跟这台机器当前的默认」，不谎称是线路设的。
    var model by remember { mutableStateOf("sonnet") }
    var effort by remember { mutableStateOf("medium") }
    var choices by remember { mutableStateOf(listOf("sonnet", "opus", "haiku", "default")) }
    var fromLine by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(ssh) {
        val s = ssh ?: return@LaunchedEffect
        // ⚠️ force：availCache 是进程级的，换线路后不重读就会拿旧的 model/effort 去起会话
        //    （还理直气壮显示「跟当前线路」）。一次 cat 而已。
        val a = runCatching { Model.available(s, hostId, force = true) }.getOrNull() ?: return@LaunchedEffect
        choices = (listOf("sonnet") + a.models).distinct()
        if (a.default.isNotBlank()) { model = a.default; fromLine = true }
        // ⚠️ 只认芯片表里的值：settings.json 里可能是 "mid"（Model.setEffort 收这个），
        //    直接拿来当选中值的话整排芯片一个都不亮，用户看不出现在是什么强度。
        if (a.effort in EFFORTS) { effort = a.effort; fromLine = true }
    }
    val pill = RoundedCornerShape(100.dp)

    @Composable fun chips(items: List<String>, cur: String, mono: Boolean, onPick: (String) -> Unit) {
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items.forEach { it ->
                val on = it == cur
                Text(
                    it,
                    Modifier.clip(pill)
                        .background(if (on) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable { onPick(it) }.padding(12.dp, 7.dp),
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default),
                    color = if (on) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(t("临时会话")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(t("在服务器 /tmp 里开一个新对话。不保存：重启 Yxi 就没了。"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                if (fromLine) Text(t("模型和思考强度跟这台机器当前的默认。"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                Text(t("模型"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                chips(choices, model, mono = true) { model = it }
                OutlinedTextField(
                    model, { model = it.trim() }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(t("也可以直接填模型名")) },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )
                Text(t("思考强度"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                chips(EFFORTS, effort, mono = true) { effort = it }
                err?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.height(2.dp))
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && ssh != null && model.isNotBlank(), onClick = {
                val s = ssh ?: return@TextButton
                // 只放白名单字符 —— 这串最终 send-keys 进 shell
                val m = model.filter { it.isLetterOrDigit() || it in "-[]._" }
                if (m.isBlank()) { err = t("模型名只能是字母数字和 -[]._"); return@TextButton }
                busy = true; err = null
                scope.launch {
                    val id = TempSessions.newId()
                    val name = TempSessions.nameOf(id); val dir = TempSessions.dirOf(id)
                    val made = app.yxi.ssh.catching { s.exec(Dirs.createCommand(dir, name, "claude", launchArgs = "--model $m --effort $effort")) }
                        .map { Dirs.madeFrom(it) }.getOrElse { Dirs.Made.Failed("unknown", it.message.orEmpty()) }
                    busy = false
                    when (made) {
                        is Dirs.Made.Failed -> err = t("没开成：%s").format(made.code + " " + made.detail)
                        else -> { TempSessions.remember(ctx, hostId, name); onOpen(name, dir) }
                    }
                }
            }) { Text(if (busy) t("开着…") else t("开始")) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(t("算了")) } },
    )
}
