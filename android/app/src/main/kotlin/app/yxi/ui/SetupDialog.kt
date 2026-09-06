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
import androidx.compose.runtime.mutableIntStateOf
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

private val PillShape = RoundedCornerShape(100.dp)

/**
 * 看板上那张「这台机器还什么都没装」的卡。只在**同步过、且 claude / codex 一个都没有**时出现，
 * 装完自己消失。⚠️ 新客户第一次连自己的服务器看到的就是它 —— 一句话说清楚缺什么、点一下能装什么。
 * 点下去开的是 [ProbeDialog]（先看清楚、再选装什么），不是直接开装。
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
                t("探测并装机"),
                Modifier.padding(top = 4.dp).clip(PillShape)
                    .background(MaterialTheme.colorScheme.primary).clickable(onClick = onSetup).padding(18.dp, 9.dp),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

/**
 * 看板上一行淡淡的入口：装是装了、但没有一个会话在跑 claude / codex 时出现。
 * 用户要的「在会话里没看见任何一个进程，就显示探测按钮」。默认什么都不做，点了才探。
 */
@Composable
internal fun ProbeRow(onProbe: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onProbe).padding(14.dp, 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            t("没看到 Claude Code / Codex 在跑"), Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
        )
        Text(t("探测 →"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

/**
 * 「探测 Claude Code / Codex」：现探这台机器装了什么、登没登录、什么系统，
 * 下面一张装机表单（都装 / 只装 Claude Code / 只装 Codex）+ 一个按钮。**默认不装**，点了才装。
 *
 * 三个地方都开它：看板的装机卡 / 探测行、主机页长按面板、「＋」里点到没装的 agent。
 * @param error 外面建连接失败的原因（主机页那条路现连）；非空就只显示它
 */
@Composable
fun ProbeDialog(ssh: SshSession?, error: String? = null, onClose: () -> Unit) {
    var probe by remember { mutableStateOf<Setup.Probe?>(null) }
    var fail by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    /** 装哪个：both / claude / codex */
    var pick by remember { mutableStateOf("both") }
    var setup by remember { mutableStateOf(false) }

    LaunchedEffect(ssh, tick) {
        val s = ssh ?: return@LaunchedEffect
        probe = null; fail = null
        val out = s.exec(Setup.PROBE_COMMAND)
        val p = Setup.parseProbe(out)
        if (p == null) fail = t("探不到（连接可能半断了）—— 再试一次") else probe = p
    }

    if (setup) {
        SetupDialog(ssh, claude = pick != "codex", codex = pick != "claude") { setup = false; tick++ }
        return
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(t("探测 Claude Code / Codex")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val p = probe
                when {
                    error != null -> Text(error, color = MaterialTheme.colorScheme.error)
                    fail != null -> Text(fail!!, color = MaterialTheme.colorScheme.error)
                    p == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(if (ssh == null) t("连接中…") else t("在探…"))
                    }
                    else -> {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            ProbeLine(
                                t("系统"),
                                listOf(p.os.ifBlank { "?" }, p.arch, if (p.root) "root" else t("非 root")).joinToString(" · "), true,
                            )
                            ProbeLine("tmux", p.tmux ?: t("没装"), p.tmux != null)
                            ProbeLine(
                                "Claude Code",
                                when {
                                    p.claude == null -> t("没装")
                                    p.claudeUser != null -> p.claude + " · " + t("已登录 %s").format(p.claudeUser)
                                    else -> p.claude + " · " + t("没登录")
                                },
                                p.claude != null,
                            )
                            ProbeLine(
                                "Codex",
                                when {
                                    p.codex == null -> t("没装")
                                    p.codexLogged -> p.codex + " · " + t("已登录")
                                    else -> p.codex + " · " + t("没登录")
                                },
                                p.codex != null,
                            )
                            ProbeLine("Node.js", p.node ?: t("没有（不需要）"), true)
                        }
                        Text(
                            when {
                                p.nothing -> t("一个都没装。选好要装什么，点「一键装机」—— tmux 和 Yxi 的钩子会一起装，不需要 Node.js。")
                                (p.claudeInstalled && p.claudeUser == null) || (p.codexInstalled && !p.codexLogged) ->
                                    t("装了但没登录 —— 去「配置 → 连接」登录。下面再装只会补缺的。")
                                else -> t("都在。下面再装只会补缺的、刷一遍 Yxi 的钩子。")
                            },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("both" to t("都装"), "claude" to "Claude Code", "codex" to "Codex").forEach { (k, label) ->
                                val on = pick == k
                                Surface(
                                    color = if (on) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                                    shape = PillShape, modifier = Modifier.clip(PillShape).clickable { pick = k },
                                ) {
                                    Text(
                                        label, Modifier.padding(12.dp, 7.dp), style = MaterialTheme.typography.labelMedium,
                                        color = if (on) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (probe != null) TextButton(onClick = { setup = true }) { Text(t("一键装机")) }
        },
        dismissButton = {
            Row {
                if (probe != null || fail != null) TextButton(onClick = { tick++ }) { Text(t("再探一次")) }
                TextButton(onClick = onClose) { Text(t("关掉")) }
            }
        },
    )
}

@Composable
private fun ProbeLine(name: String, value: String, ok: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            name, Modifier.padding(end = 2.dp), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            value, style = MaterialTheme.typography.bodySmall,
            color = if (ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
        )
    }
}

/**
 * 装机进度框：起脚本、每秒看一眼日志尾巴、结束了说结果。
 *
 * ⚠️ 关掉框**不停装** —— 脚本在服务器上 nohup 跑着，再打开接着看。
 * @param claude / [codex] 装哪个；tmux 和 Yxi 钩子总是装
 * @param onClose true = 装好了；false = 有项没装成；null = 用户中途关了框
 */
@Composable
fun SetupDialog(ssh: SshSession?, claude: Boolean = true, codex: Boolean = true, onClose: (Boolean?) -> Unit) {
    var log by remember { mutableStateOf("") }
    var done by remember { mutableStateOf<Boolean?>(null) }
    val what = listOfNotNull("tmux", if (claude) "Claude Code" else null, if (codex) "Codex" else null, t("Yxi 钩子")).joinToString(" · ")

    LaunchedEffect(ssh) {
        val s = ssh ?: run { log = t("还没连上"); return@LaunchedEffect }
        val first = s.exec(Setup.tailCommand())
        if (!Setup.running(first)) s.exec(Setup.startCommand(Setup.fetchScript(), claude, codex))
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
                        null -> { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Text(t("在装：%s（几分钟）").format(what)) }
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
            if (done == null) TextButton(onClick = { onClose(null) }) { Text(t("关掉（后台继续装）")) }
        },
    )
}
