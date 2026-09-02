package app.yxi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.yxi.agent.Setup
import app.yxi.ssh.SshSession
import kotlinx.coroutines.delay

/**
 * 看板上那张「这台机器还什么都没装」的卡。只在**同步过、且 claude / codex 一个都没有**时出现，
 * 装完自己消失。⚠️ 新客户第一次连自己的服务器看到的就是它 —— 一句话说清楚缺什么、点一下能装什么。
 */
@Composable
internal fun SetupCard(missingTmux: Boolean, onSetup: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp, 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                t("这台机器上还没有 Claude Code / Codex"),
                style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                if (missingTmux) t("连 tmux 也没有 —— 一键装机会把 tmux、Claude Code、Codex 和 Yxi 的钩子一起装上，不需要 Node.js。")
                else t("一键装机会把 Claude Code、Codex 和 Yxi 的钩子装上，不需要 Node.js。装完在「配置 → 连接」里登录。"),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                t("一键装机"),
                Modifier.padding(top = 4.dp).clip(RoundedCornerShape(100.dp))
                    .background(MaterialTheme.colorScheme.primary).clickable(onClick = onSetup).padding(18.dp, 9.dp),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

/**
 * 装机进度框：起脚本、每秒看一眼日志尾巴、结束了说结果。
 *
 * ⚠️ 关掉框**不停装** —— 脚本在服务器上 nohup 跑着，再打开接着看。
 * @param onClose true = 装好了；false = 有项没装成；null = 用户中途关了框
 */
@Composable
fun SetupDialog(ssh: SshSession?, onClose: (Boolean?) -> Unit) {
    var log by remember { mutableStateOf("") }
    var done by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(ssh) {
        val s = ssh ?: run { log = t("还没连上"); return@LaunchedEffect }
        val first = s.exec(Setup.tailCommand())
        if (!Setup.running(first)) s.exec(Setup.startCommand(Setup.fetchScript()))
        // 最多盯 25 分钟：Claude Code 100MB + Codex 90MB，慢机器上就是这个量级
        for (i in 0 until 1500) {
            delay(1000)
            val tail = s.exec(Setup.tailCommand())
            if (tail.isNotBlank()) log = tail
            Setup.done(tail)?.let { done = it; return@LaunchedEffect }
        }
        done = false
        log = log + "\n" + t("等了 25 分钟还没结束 —— 用终端看看 ~/.yxi/setup.log")
    }

    AlertDialog(
        onDismissRequest = { onClose(null) },
        title = { Text(t("一键装机")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    when (done) {
                        null -> { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Text(t("在装：tmux · Claude Code · Codex · Yxi 钩子（几分钟）")) }
                        true -> Text(t("装好了。下一步：「配置 → 连接」里登录 Claude Code / Codex。"), color = MaterialTheme.colorScheme.primary)
                        false -> Text(t("有几项没装成 —— 看下面 ⚠ 的行；再点一次只补没装的。"), color = MaterialTheme.colorScheme.error)
                    }
                }
                Box(
                    Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(10.dp)
                        .verticalScroll(rememberScrollState(), reverseScrolling = true),
                ) {
                    Text(
                        Setup.pretty(log).ifBlank { "…" },
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            if (done != null) TextButton(onClick = { onClose(done) }) { Text(t("好")) }
        },
        dismissButton = {
            if (done == null) TextButton(onClick = { onClose(null) }) { Text(t("先关掉（后台继续装）")) }
        },
    )
}
