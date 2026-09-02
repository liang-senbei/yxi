package app.yxi.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.yxi.agent.Connect
import app.yxi.ssh.SshSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 「连接」面板：把 GitHub / Notion / Linear … 接给那台机器上的 agent。逻辑见 [Connect]。
 *
 * 每一行：名字 + 接上能干什么 + 状态 + 一个按钮。点「连接」弹一个步骤框，
 * 框里只做三件事：① 显示码 / 打开授权页 ② 等 ③ 说结果。
 * ⚠️ 框关掉**不杀服务器那头的流程** —— 用户可能是切去浏览器了；等他回来刷新一下就是最新状态。
 */
@Composable
fun ConnectPanel(ssh: SshSession?, host: app.yxi.ssh.Host) {
    var status by remember(host.id) { mutableStateOf<Connect.Status?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var flow by remember { mutableStateOf<Flow?>(null) }
    var custom by remember { mutableStateOf(false) }
    var confirmDrop by remember { mutableStateOf<Connect.Service?>(null) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    LaunchedEffect(ssh, host.id, tick) {
        if (tick == 0) status = null
        status = Connect.status(ssh)
    }

    val st = status
    when {
        ssh == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(t("还没连上"), color = MaterialTheme.colorScheme.outline)
        }
        st == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(30.dp), strokeWidth = 2.5.dp)
        }
        else -> LazyColumn(
            Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp, 4.dp, 14.dp, 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    t("认证一次，这台机器上的 Claude Code 就能直接调用它。全程在手机上完成。"),
                    Modifier.padding(4.dp, 2.dp, 4.dp, 6.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                )
            }
            if (!st.claudeInstalled) item {
                Note(t("这台机器上没有 claude 命令，MCP 那几项接不了；GitHub 仍然可以。"))
            }
            items(Connect.CATALOG, key = { it.key }) { s ->
                ServiceRow(
                    s, st.of(s),
                    enabled = when (s.kind) {
                        Connect.Kind.GH -> st.ghInstalled
                        Connect.Kind.MCP -> st.claudeInstalled
                        Connect.Kind.INFO -> false
                    },
                    onConnect = { flow = Flow(ssh, s, scope) { tick++ } },
                    onDrop = { confirmDrop = s },
                )
            }
            item {
                Text(
                    t("+ 自定义 MCP 地址…"),
                    Modifier.clip(RoundedCornerShape(100.dp)).clickable(enabled = st.claudeInstalled) { custom = true }
                        .padding(16.dp, 12.dp),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }

    flow?.let { f ->
        FlowDialog(f, onClose = { f.cancelPolling(); flow = null; tick++ }, onOpen = { url ->
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { android.widget.Toast.makeText(ctx, t("打不开浏览器：%s").format(it.message ?: ""), android.widget.Toast.LENGTH_LONG).show() }
        }, onCopy = { code ->
            val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("GitHub", code))
        })
    }

    if (custom) CustomMcpDialog(onCancel = { custom = false }) { name, url ->
        custom = false
        val s = Connect.Service(name, name, url, url, if (url.endsWith("/sse")) "sse" else "http")
        ssh?.let { live -> flow = Flow(live, s, scope) { tick++ } }
    }

    confirmDrop?.let { s ->
        AlertDialog(
            onDismissRequest = { confirmDrop = null },
            title = { Text(t("断开 %s？").format(s.name)) },
            text = { Text(if (s.kind == Connect.Kind.GH) t("会退出 gh 的登录，git 推拉和 GitHub MCP 一起失效。") else t("会从 Claude Code 的配置里删掉这个 MCP。")) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDrop = null
                    scope.launch {
                        ssh?.exec(if (s.kind == Connect.Kind.GH) Connect.ghLogout() else Connect.mcpRemove(s.key))
                        tick++
                    }
                }) { Text(t("断开"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDrop = null }) { Text(t("算了")) } },
        )
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text, Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow).padding(14.dp, 10.dp),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ServiceRow(
    s: Connect.Service, state: Connect.State, enabled: Boolean,
    onConnect: () -> Unit, onDrop: () -> Unit,
) {
    val info = s.kind == Connect.Kind.INFO
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(s.name, style = MaterialTheme.typography.titleSmall,
                        color = if (info) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface)
                    when (state) {
                        Connect.State.CONNECTED -> Chip(t("已连接"), MaterialTheme.colorScheme.primary)
                        Connect.State.NEEDS_AUTH -> Chip(t("要登录"), MaterialTheme.colorScheme.tertiary)
                        Connect.State.FAILED -> Chip(t("连不上"), MaterialTheme.colorScheme.error)
                        Connect.State.ABSENT -> {}
                    }
                }
                Text(t(s.what), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            if (!info) {
                Spacer(Modifier.width(10.dp))
                val label = when {
                    state == Connect.State.CONNECTED -> t("断开")
                    s.noAuth -> t("加上")
                    state == Connect.State.NEEDS_AUTH -> t("登录")
                    else -> t("连接")
                }
                val primary = state != Connect.State.CONNECTED
                Text(
                    label,
                    Modifier.clip(RoundedCornerShape(100.dp))
                        .background(
                            if (primary && enabled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceContainerHigh,
                        )
                        .clickable(enabled = enabled) { if (state == Connect.State.CONNECTED) onDrop() else onConnect() }
                        .padding(16.dp, 9.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (primary && enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Chip(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text, Modifier.clip(RoundedCornerShape(100.dp)).background(color.copy(alpha = 0.14f)).padding(8.dp, 2.dp),
        style = MaterialTheme.typography.labelSmall, color = color,
    )
}

/**
 * 一次连接流程。状态机在这儿，界面只画 [step]。
 *
 * GitHub：起 `gh auth login` → 屏幕上等一次性码 → 用户去 github.com/login/device 输码 → 等 `__DONE__`。
 * MCP：`claude mcp add` → 起 `claude mcp login --no-browser` → 屏幕上等授权 URL →
 *      转发回调端口 → 用户在浏览器授权 → 等 `__DONE__`。
 * 免认证的 MCP：`add` 完就算完。
 */
class Flow(
    private val ssh: SshSession,
    val service: Connect.Service,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val onChanged: () -> Unit,
) {
    sealed class Step {
        data class Working(val what: String) : Step()
        /** GitHub：码 + 去哪输 */
        data class Code(val code: String, val url: String) : Step()
        /** MCP：授权页 + 回调端口是否已转发 */
        data class Authorize(val url: String, val forwarded: Boolean) : Step()
        data class Done(val ok: Boolean, val message: String) : Step()
    }

    var step: Step by mutableStateOf(Step.Working(t("准备中…")))
        private set
    /** 用户已经点过「去认证」—— 之后步骤框只显示「等着」 */
    var opened by mutableStateOf(false)
    private var job: Job? = null
    private var port: Int? = null
    private val tmux = Connect.tmuxFor(service.key)

    init { job = scope.launch { runCatching { run() }.onFailure { step = Step.Done(false, it.message ?: "?") } } }

    private suspend fun run() {
        when (service.kind) {
            Connect.Kind.GH -> gh()
            Connect.Kind.MCP -> mcp()
            Connect.Kind.INFO -> step = Step.Done(false, "")
        }
    }

    private suspend fun gh() {
        step = Step.Working(t("在服务器上起 gh 登录…"))
        ssh.exec(Connect.ghLoginStart())
        var enterSent = false
        var code: String? = null
        for (i in 0 until 60) {
            delay(500)
            val pane = ssh.exec(Connect.peekCommand(tmux))
            if (Connect.ghAsksGit(pane)) { ssh.exec(Connect.enterCommand(tmux)); continue }
            code = Connect.ghCode(pane)
            if (code != null) {
                if (Connect.ghAsksOpen(pane) && !enterSent) { ssh.exec(Connect.enterCommand(tmux)); enterSent = true }
                break
            }
            Connect.parseDone(pane)?.let { if (!it) { step = Step.Done(false, Connect.failReason(pane)); return } }
        }
        val c = code ?: run { step = Step.Done(false, t("gh 没给出一次性码（这台机器装了 gh 吗？）")); return }
        step = Step.Code(c, Connect.GH_DEVICE_URL)
        waitDone { ssh.exec(Connect.GH_AFTER_COMMAND); t("GitHub 接上了：git 推拉和 GitHub MCP 都能用了") }
    }

    private suspend fun mcp() {
        step = Step.Working(t("加进 Claude Code…"))
        val out = ssh.exec(Connect.mcpAdd(service))
        if (out.contains("error", ignoreCase = true) && !out.contains("already", ignoreCase = true)) {
            step = Step.Done(false, out.trim().take(160)); return
        }
        if (service.noAuth) { step = Step.Done(true, t("加上了，不用登录，直接就能用")); onChanged(); return }
        step = Step.Working(t("等授权地址…"))
        ssh.exec(Connect.mcpLoginStart(service.key))
        var url: String? = null
        for (i in 0 until 40) {
            delay(500)
            val pane = ssh.exec(Connect.peekCommand(tmux))
            url = Connect.loginUrl(pane)
            if (url != null) break
            Connect.parseDone(pane)?.let { if (!it) { step = Step.Done(false, Connect.failReason(pane)); return } }
        }
        val u = url ?: run { step = Step.Done(false, t("没拿到授权地址")); return }
        // 回调端口转发到手机上：浏览器授权完跳 localhost:<端口>，直接落到服务器
        val p = Connect.callbackPort(u)
        val forwarded = p != null && ssh.forwardLocal(p)
        if (forwarded) port = p
        step = Step.Authorize(u, forwarded)
        waitDone { t("%s 接上了").format(service.name) }
    }

    /** 等服务器那头出结果，最多 10 分钟。 */
    private suspend fun waitDone(after: suspend () -> String) {
        for (i in 0 until 400) {
            delay(1500)
            val pane = ssh.exec(Connect.peekCommand(tmux))
            when (Connect.parseDone(pane)) {
                true -> { val msg = after(); finish(); step = Step.Done(true, msg); onChanged(); return }
                false -> { finish(); step = Step.Done(false, Connect.failReason(pane)); return }
                null -> {}
            }
        }
        finish()
        step = Step.Done(false, t("等了 10 分钟没结果，服务器那头的流程已经停了"))
    }

    /** 退路：把浏览器地址栏那串 localhost 地址粘回去 */
    fun paste(redirectUrl: String) {
        scope.launch { ssh.exec(Connect.pasteCommand(service.key, redirectUrl)) }
    }

    private suspend fun finish() {
        port?.let { ssh.unforwardLocal(it) }; port = null
        ssh.exec(Connect.killCommand(tmux))
    }

    /** 关框：停止轮询、收回端口，**不杀服务器那头**（用户可能只是切去浏览器了） */
    fun cancelPolling() {
        job?.cancel()
        port?.let { p -> scope.launch { ssh.unforwardLocal(p) } }
    }
}

@Composable
private fun FlowDialog(f: Flow, onClose: () -> Unit, onOpen: (String) -> Unit, onCopy: (String) -> Unit) {
    var pasted by remember { mutableStateOf("") }
    val step = f.step
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(t("连接 %s").format(f.service.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (step) {
                    is Flow.Step.Working -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(step.what)
                    }
                    is Flow.Step.Code -> {
                        Text(t("GitHub 页面会要一个一次性码。点下面按钮：码已复制、页面已打开，粘进去按确认就行。"))
                        Text(step.code, fontFamily = FontFamily.Monospace, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary)
                        Text(
                            if (f.opened) t("等 GitHub 那边确认…（确认完这里会自己变）") else t("码 15 分钟内有效"),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    is Flow.Step.Authorize -> {
                        Text(
                            if (step.forwarded) t("在浏览器里登录并同意。同意之后页面会自己跳回来，这里会显示接上了。")
                            else t("在浏览器里登录并同意。同意之后浏览器会停在一个打不开的 localhost 页面 —— 把地址栏那串地址复制过来粘到下面。"),
                        )
                        if (f.opened) Text(t("等浏览器那边授权…"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        OutlinedTextField(
                            pasted, { pasted = it }, Modifier.fillMaxWidth(),
                            label = { Text(t("跳不回来？把 localhost 开头的地址粘这儿")) }, singleLine = true,
                        )
                        if (pasted.startsWith("http")) TextButton(onClick = { f.paste(pasted.trim()); pasted = "" }) { Text(t("交上去")) }
                    }
                    is Flow.Step.Done -> Text(step.message, color = if (step.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            when (step) {
                is Flow.Step.Code -> TextButton(onClick = { onCopy(step.code); f.opened = true; onOpen(step.url) }) { Text(t("复制码并打开 GitHub")) }
                is Flow.Step.Authorize -> TextButton(onClick = { f.opened = true; onOpen(step.url) }) { Text(t("去浏览器授权")) }
                is Flow.Step.Done -> TextButton(onClick = onClose) { Text(t("好")) }
                is Flow.Step.Working -> {}
            }
        },
        dismissButton = { if (step !is Flow.Step.Done) TextButton(onClick = onClose) { Text(t("先关掉")) } },
    )
}

@Composable
private fun CustomMcpDialog(onCancel: () -> Unit, onAdd: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("https://") }
    val okName = name.matches(Regex("[A-Za-z0-9_-]{1,40}"))
    val okUrl = url.startsWith("https://") && url.length > 10
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(t("自定义 MCP")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(t("名字（字母数字）")) }, singleLine = true)
                OutlinedTextField(url, { url = it }, label = { Text(t("地址（https://…/mcp 或 /sse）")) }, singleLine = true)
                Text(t("加进去之后会走一遍登录；不需要登录的服务登录那步会自己过。"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        },
        confirmButton = { TextButton(enabled = okName && okUrl, onClick = { onAdd(name.trim(), url.trim()) }) { Text(t("连接")) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(t("算了")) } },
    )
}
