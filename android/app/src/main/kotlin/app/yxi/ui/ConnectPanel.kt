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
    var setup by remember { mutableStateOf(false) }
    var setupOnly by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    // 授权流程要等十分钟，中途连接大概率被换掉 —— 统一走这一份（TROUBLESHOOTING #282）
    val alive = rememberAliveSsh(ssh)

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
                    t("认证一次，agent 即可调用。"),
                    Modifier.padding(4.dp, 2.dp, 4.dp, 6.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                )
            }
            if (!st.tmuxInstalled) item {
                Note(t("这台机器上没有 tmux —— 登录和会话都靠它。点上面「安装」一键装机，会一起装上。"))
            } else if (!st.claudeInstalled) item {
                Note(t("这台机器上没有 claude 命令，MCP 那几项接不了；GitHub 仍然可以。"))
            }
            var lastCat = ""
            Connect.CATALOG.forEach { s ->
                if (s.cat != lastCat && s.cat.isNotBlank()) {
                    lastCat = s.cat
                    val c = s.cat
                    item(key = "cat-$c") {
                        Text(t(c), Modifier.padding(4.dp, 14.dp, 4.dp, 2.dp),
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
                item(key = s.key) {
                    ServiceRow(
                        s, st.of(s),
                        enabled = when (s.kind) {
                            Connect.Kind.LOCAL -> true
                            Connect.Kind.INFO -> false
                            Connect.Kind.AGENT -> st.tmuxInstalled
                            Connect.Kind.GH -> st.tmuxInstalled && st.ghInstalled
                            Connect.Kind.MCP -> st.tmuxInstalled && st.claudeInstalled
                        },
                        installed = st.installed(s),
                        extra = if (s.kind == Connect.Kind.AGENT && s.key == "claude") st.claudeUser else null,
                        onConnect = { flow = Flow(alive, s, scope) { tick++ } },
                        onDrop = { confirmDrop = s },
                        onInstall = { setupOnly = s.key; setup = true },
                    )
                }
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

    // 回调回来了但连接框已经关了 —— 别一声不响地吞掉。
    // ⚠️ 这时候那串码基本是废的：重开流程会 kill 掉旧的 tmux 会话重来一遍。
    //    所以只能如实说「这次白跑了，重开一次」，不能假装能用。
    LaunchedEffect(app.yxi.agent.Connect.pendingRedirect, flow) {
        if (flow != null) return@LaunchedEffect
        if (app.yxi.agent.Connect.pendingRedirect == null) return@LaunchedEffect
        app.yxi.agent.Connect.pendingRedirect = null
        android.widget.Toast.makeText(
            ctx, t("认证回来了，但连接框已经关了 —— 重新点一次「连接」"), android.widget.Toast.LENGTH_LONG,
        ).show()
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

    if (setup) SetupDialog(ssh, claude = setupOnly != "codex", codex = setupOnly != "claude") { setup = false; setupOnly = null; tick++ }

    if (custom) CustomMcpDialog(onCancel = { custom = false }) { name, url ->
        custom = false
        val s = Connect.Service(name, name, url, url, if (url.endsWith("/sse")) "sse" else "http")
        if (ssh != null) flow = Flow(alive, s, scope) { tick++ }
    }

    confirmDrop?.let { s ->
        AlertDialog(
            onDismissRequest = { confirmDrop = null },
            title = { Text(t("断开 %s？").format(s.name)) },
            text = {
                Text(
                    when (s.kind) {
                        Connect.Kind.AGENT -> t("会退出这台机器上 %s 的登录，会话里再用得重新登。").format(s.name)
                        Connect.Kind.GH -> t("会退出 gh 的登录，git 推拉和 GitHub MCP 一起失效。")
                        Connect.Kind.LOCAL -> t("会清除密钥缓存，下次使用需要重新提取。")
                        else -> t("会从 Claude Code 的配置里删掉这个 MCP。")
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDrop = null
                    scope.launch {
                        ssh?.exec(
                            when (s.kind) {
                                Connect.Kind.AGENT -> Connect.agentLogout(s.key)
                                Connect.Kind.GH -> Connect.ghLogout()
                                Connect.Kind.LOCAL -> Connect.localDisconnect(s.key)
                                else -> Connect.mcpRemove(s.key)
                            },
                        )
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
    /** 没装 → 按钮变「安装」（一键装机），状态芯片写「没装」 */
    installed: Boolean = true,
    /** 附在说明后面的一小段（登录的账号之类） */
    extra: String? = null,
    onInstall: () -> Unit = {},
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
                    when {
                        !installed -> Chip(t("没装"), MaterialTheme.colorScheme.outline)
                        state == Connect.State.CONNECTED ->
                            Chip(if (s.kind == Connect.Kind.AGENT) t("已登录") else t("已连接"), MaterialTheme.colorScheme.primary)
                        state == Connect.State.NEEDS_AUTH -> Chip(t("要登录"), MaterialTheme.colorScheme.tertiary)
                        state == Connect.State.FAILED -> Chip(t("连不上"), MaterialTheme.colorScheme.error)
                        else -> {}
                    }
                }
                Text(
                    t(s.what) + (extra?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                )
            }
            if (!info) {
                Spacer(Modifier.width(10.dp))
                val label = when {
                    !installed -> t("安装")
                    state == Connect.State.CONNECTED -> if (s.kind == Connect.Kind.AGENT) t("退出") else t("断开")
                    s.noAuth -> t("加上")
                    state == Connect.State.NEEDS_AUTH || s.kind == Connect.Kind.AGENT -> t("登录")
                    else -> t("连接")
                }
                val primary = state != Connect.State.CONNECTED
                // 「安装」不靠 tmux，没 tmux 也要能点 —— 它正是把 tmux 装上的那条路
                val active = enabled || !installed
                Text(
                    label,
                    Modifier.clip(RoundedCornerShape(100.dp))
                        .background(
                            if (primary && active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceContainerHigh,
                        )
                        .clickable(enabled = active) {
                            when {
                                !installed -> onInstall()
                                state == Connect.State.CONNECTED -> onDrop()
                                else -> onConnect()
                            }
                        }
                        .padding(16.dp, 9.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (primary && active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
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
    /**
     * ⚠️⚠️ **这里刻意不接 `SshSession`，接的是「给我一条活的」那个函数**（[rememberAliveSsh]，TROUBLESHOOTING #282）。
     * 这条流程最长等 **10 分钟**，中间用户会切去浏览器授权、锁屏、换网 —— 手机上重连是常态，
     * 抓在手里的那个 `SshSession` 到时候几乎必然已经死了。原来的写法后果是**误报失败**：
     * 连接一死 `exec` 抛异常 → 弹「没成功」，可服务器上的 tmux 流程其实好好地跑完了。
     * 用户以为坏了、反复重来，而每重来一次 `mcpLoginStart` 会把服务器上那个正常的会话杀掉重开。
     */
    private val alive: suspend (Long) -> SshSession?,
    val service: Connect.Service,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val onChanged: () -> Unit,
) {
    sealed class Step {
        data class Working(val what: String) : Step()
        /** GitHub：码 + 去哪输 */
        data class Code(val code: String, val url: String) : Step()
        /**
         * MCP：授权页 + 回调端口是否已转发。
         * Claude Code 登录也用它，但 [code] = true：授权完页面**给一串码**，粘回来（不是 localhost 地址）。
         */
        data class Authorize(val url: String, val forwarded: Boolean, val code: Boolean = false) : Step()
        data class Done(val ok: Boolean, val message: String) : Step()
    }

    var step: Step by mutableStateOf(Step.Working(t("准备中…")))
        private set
    /** 用户已经点过「去认证」—— 之后步骤框只显示「等着」 */
    var opened by mutableStateOf(false)
    /** 粘回去的码被拒了之类的提示（空 = 没有）。⚠️ 没有它，粘错码只会看到输入框被清空、什么都不说（模拟器实测）。 */
    var hint by mutableStateOf("")
    /**
     * 「交上去」之后的中性反馈（空 = 没有）。⚠️ 跟 [hint] 分开：那个是红色的「你错了」，
     * 这个是「收到了，等结果」。没有它，点完「交上去」输入框和按钮一起消失、
     * 上面还写着「等浏览器那边授权…」—— 用户完全看不出到底交没交上（老板 2026-09-06 报的）。
     */
    var note by mutableStateOf("")
    private var job: Job? = null
    /** 端口转发绑在**具体某条连接**上：连接换了，转发跟着没了，所以要记住是哪条 */
    private var forwarded: Pair<SshSession, Int>? = null
    private val tmux = Connect.tmuxFor(service.key)

    /** 一次性动作用：等一条活的（最多 20 秒），拿不到就 null —— 由调用处如实报错。 */
    private suspend fun once(cmd: String): String? = alive(20_000)?.exec(cmd)

    /**
     * 轮询用：**拿不到连接就返回 null，让这一轮跳过**，不是失败。
     * 服务器那头的 tmux 流程照跑，我们只是这一下看不见 —— 这正是原来误报失败的地方。
     */
    private suspend fun peek(cmd: String): String? = alive(0)?.let { runCatching { it.exec(cmd) }.getOrNull() }

    init {
        // ⚠️ 开新流程时把上一次遗留的回调丢掉：浏览器可能在没开框的时候把 localhost 地址
        //    交给过我们（那会儿没人接），留着的话会被下一个流程当成自己的结果吃掉。
        Connect.pendingRedirect = null
    }

    init { job = scope.launch { runCatching { run() }.onFailure { step = Step.Done(false, it.message ?: "?") } } }

    private suspend fun run() {
        when (service.kind) {
            Connect.Kind.AGENT -> if (service.key == "claude") claude() else codex()
            Connect.Kind.GH -> gh()
            Connect.Kind.MCP -> mcp()
            Connect.Kind.LOCAL -> local()
            Connect.Kind.INFO -> step = Step.Done(false, "")
        }
    }

    /** Claude Code：起 `claude auth login` → 等授权 URL → 用户登录、页面给码 → 粘回去 → 等 `__DONE__` */
    private suspend fun claude() {
        step = Step.Working(t("在服务器上起 Claude Code 登录…"))
        once(Connect.claudeLoginStart()) ?: run { step = Step.Done(false, t("连接断了，等不到重连")); return }
        var url: String? = null
        for (i in 0 until 40) {
            delay(500)
            val pane = peek(Connect.peekCommand(tmux)) ?: continue
            url = Connect.claudeUrl(pane)
            if (url != null) break
            Connect.parseDone(pane)?.let { if (!it) { step = Step.Done(false, Connect.failReason(pane)); return } }
        }
        val u = url ?: run { step = Step.Done(false, t("没拿到登录地址（这台机器装了 claude 吗？）")); return }
        step = Step.Authorize(u, forwarded = false, code = true)
        waitDone { t("Claude Code 登录好了，会话里直接能用") }
    }

    /** Codex：起 `codex login --device-auth` → 屏幕上等一次性码 → 用户去 auth.openai.com/codex/device 输码 → 等 `__DONE__` */
    private suspend fun codex() {
        step = Step.Working(t("在服务器上起 Codex 登录…"))
        once(Connect.codexLoginStart()) ?: run { step = Step.Done(false, t("连接断了，等不到重连")); return }
        var code: String? = null
        for (i in 0 until 60) {
            delay(500)
            val pane = peek(Connect.peekCommand(tmux)) ?: continue
            code = Connect.codexCode(pane)
            if (code != null) break
            Connect.parseDone(pane)?.let { if (!it) { step = Step.Done(false, Connect.failReason(pane)); return } }
        }
        val c = code ?: run { step = Step.Done(false, t("Codex 没给出一次性码（这台机器装了 codex 吗？）")); return }
        step = Step.Code(c, Connect.CODEX_DEVICE_URL)
        waitDone { t("Codex 登录好了，会话里直接能用") }
    }

    private suspend fun gh() {
        step = Step.Working(t("在服务器上起 gh 登录…"))
        once(Connect.ghLoginStart()) ?: run { step = Step.Done(false, t("连接断了，等不到重连")); return }
        var enterSent = false
        var code: String? = null
        for (i in 0 until 60) {
            delay(500)
            val pane = peek(Connect.peekCommand(tmux)) ?: continue
            if (Connect.ghAsksGit(pane)) { peek(Connect.enterCommand(tmux)); continue }
            code = Connect.ghCode(pane)
            if (code != null) {
                if (Connect.ghAsksOpen(pane) && !enterSent) { peek(Connect.enterCommand(tmux)); enterSent = true }
                break
            }
            Connect.parseDone(pane)?.let { if (!it) { step = Step.Done(false, Connect.failReason(pane)); return } }
        }
        val c = code ?: run { step = Step.Done(false, t("gh 没给出一次性码（这台机器装了 gh 吗？）")); return }
        step = Step.Code(c, Connect.GH_DEVICE_URL)
        // ⚠️ 这条要**真的执行到**（把 gh 的 token 配给 git + 加 GitHub MCP），所以用 once 等一条活的；
        //    但只等 5 秒，别把「显示成功」拖太久 —— 等不到就算了，用户回头刷新状态还能看到 gh 已登录。
        waitDone { alive(5_000)?.exec(Connect.GH_AFTER_COMMAND); t("GitHub 接上了：git 推拉和 GitHub MCP 都能用了") }
    }

    private suspend fun mcp() {
        step = Step.Working(t("加进 Claude Code…"))
        val out = once(Connect.mcpAdd(service)) ?: run { step = Step.Done(false, t("连接断了，等不到重连")); return }
        if (out.contains("error", ignoreCase = true) && !out.contains("already", ignoreCase = true)) {
            step = Step.Done(false, out.trim().take(160)); return
        }
        if (service.noAuth) { step = Step.Done(true, t("加上了，不用登录，直接就能用")); onChanged(); return }
        step = Step.Working(t("等授权地址…"))
        once(Connect.mcpLoginStart(service.key)) ?: run { step = Step.Done(false, t("连接断了，等不到重连")); return }
        var url: String? = null
        for (i in 0 until 40) {
            delay(500)
            val pane = peek(Connect.peekCommand(tmux)) ?: continue
            url = Connect.loginUrl(pane)
            if (url != null) break
            Connect.parseDone(pane)?.let { if (!it) { step = Step.Done(false, Connect.failReason(pane)); return } }
        }
        val u = url ?: run { step = Step.Done(false, t("没拿到授权地址")); return }
        // 回调端口转发到手机上：浏览器授权完跳 localhost:<端口>，直接落到服务器
        val p = Connect.callbackPort(u)
        val ok = p != null && (alive(20_000)?.let { live ->
            runCatching { live.forwardLocal(p) }.getOrDefault(false).also { if (it) forwarded = live to p }
        } ?: false)
        step = Step.Authorize(u, ok)
        waitDone { t("%s 接上了").format(service.name) }
    }

    /** 本地应用（微信）：SSH 到电脑 → 提取密钥。不走 tmux，直接 exec。 */
    private suspend fun local() {
        step = Step.Working(t("连接电脑…"))
        val ping = once("""ssh -o ConnectTimeout=3 -o BatchMode=yes laptop "echo OK" 2>/dev/null""")
        if (ping == null) { step = Step.Done(false, t("连接断了，等不到重连")); return }
        if (!ping.contains("OK")) {
            step = Step.Done(false, t("连不上电脑 —— 检查反向隧道是否正常")); return
        }
        step = Step.Working(t("提取微信密钥…"))
        // 提密钥要十几秒，中间连接可能被换掉 —— 同样走 once（TROUBLESHOOTING #282）
        once("""ssh -o ConnectTimeout=15 laptop "set ELECTRON_RUN_AS_NODE=1 && E:\weflow\WeFlow.exe C:\temp\getkey.js" 2>&1""")
        // getkey.js 退出时文件已经写完（同步写），这 300ms 只是给 Windows 的文件系统缓存一点余量；
        // 万一没赶上，用户再点一次「连接」就好，不会坏事
        delay(300)
        val check = once("""ssh -o ConnectTimeout=3 laptop "if exist C:\temp\decrypted_key.txt echo KEY_OK" 2>/dev/null""")
        if (check == null) { step = Step.Done(false, t("连接断了，等不到重连")); return }
        if (check.contains("KEY_OK")) {
            step = Step.Done(true, t("微信已连接")); onChanged()
        } else {
            step = Step.Done(false, t("密钥提取失败 —— WeFlow 在运行吗？"))
        }
    }

    /** 等服务器那头出结果，最多 10 分钟。 */
    private suspend fun waitDone(after: suspend () -> String) {
        for (i in 0 until 400) {
            delay(1500)
            // ⚠️ **连接断了不算失败**：跳过这一轮就行，服务器那头的 tmux 照跑。
            //    原来这里直接 `ssh.exec`，连接一死就抛异常 → 弹「没成功」，而授权其实成功了。
            val pane = peek(Connect.peekCommand(tmux)) ?: continue
            reforwardIfNeeded()
            // 登录成功后有的工具会停在「Press Enter to continue」—— 替用户按了，不然要等到超时
            if (pane.contains("Press Enter to continue")) peek(Connect.enterCommand(tmux))
            if (pane.contains("Invalid code")) { note = ""; hint = t("码不对 —— 回页面把整串码重新复制一遍") }
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
        scope.launch {
            hint = ""; note = t("交上去了，等结果…")
            // ⚠️ 先确认服务器那头还在：会话早没了的话 send-keys 是**静默的空操作**，
            //    用户会盯着「等着」一直等到 10 分钟超时，什么都不知道。
            // ⚠️⚠️ 但**「空」不等于「没了」**：exec 连接断了也返回空串。只有明确读到
            //    __GONE__ 才敢说流程结束；读到空就当不知道，照发，让 waitDone 去判。
            val gone = once(Connect.aliveCommand(service.key))
            if (gone == null) { note = ""; hint = t("连接断了，等不到重连"); return@launch }
            if (gone.contains("__GONE__")) {
                note = ""; hint = t("服务器那头的流程已经结束了 —— 关掉重来一次")
                return@launch
            }
            once(Connect.pasteCommand(service.key, redirectUrl))
        }
    }

    /** 连接换过了就把回调端口在新连接上重转一次 —— 不然浏览器授权完跳 localhost 会落空 */
    private suspend fun reforwardIfNeeded() {
        val (old, p) = forwarded ?: return
        if (old.isAlive) return
        val live = alive(0) ?: return
        if (runCatching { live.forwardLocal(p) }.getOrDefault(false)) forwarded = live to p
    }

    /**
     * 收尾：撤端口转发 + 杀掉服务器上那个 tmux。
     * ⚠️ **用 `peek` 不用 `once`**：这是尽力而为的清理，不该让人等。手机正好离线时 `once` 会为它干等 20 秒，
     *   而它就卡在「判定成功」和「显示成功」之间 —— 用户盯着「等着」多等 20 秒（gh 那条还要再加一次，共 40 秒）。
     *   杀不掉也无所谓：那个 tmux 自带 `sleep 900` 会自己退，下次开同名流程也会先 kill 一次（审查查出）。
     */
    private suspend fun finish() {
        forwarded?.let { (s, p) -> runCatching { s.unforwardLocal(p) } }; forwarded = null
        peek(Connect.killCommand(tmux))
    }

    /** 关框：停止轮询、收回端口，**不杀服务器那头**（用户可能只是切去浏览器了） */
    fun cancelPolling() {
        job?.cancel()
        forwarded?.let { (s, p) -> scope.launch { runCatching { s.unforwardLocal(p) } } }
        forwarded = null
    }
}

@Composable
private fun FlowDialog(f: Flow, onClose: () -> Unit, onOpen: (String) -> Unit, onCopy: (String) -> Unit) {
    var pasted by remember { mutableStateOf("") }
    val step = f.step
    // 浏览器把 http://localhost:<口>/callback 交给 Yxi 了（manifest 那条 intent-filter）——
    // **只填进输入框，不替用户交上去**。
    // ⚠️ 这条 intent-filter 是导出的：手机上任何 app 都能拿构造的 localhost/callback 唤起我们。
    //    自动提交等于让别人隔空往你服务器的会话里塞东西（转义是干净的，但内容不是我们能信的）。
    //    填进框里、由人按一下，省掉手抄的麻烦，又留了一道人眼。
    // ⚠️ 无论当前哪一步都**立刻收走**：留着的话会在几秒后步骤翻到 Authorize 时自己冒出来，
    //    那多半是上一轮的陈货（新流程已经把旧会话杀了重开，那串码是死的）。
    LaunchedEffect(app.yxi.agent.Connect.pendingRedirect, step) {
        val url = app.yxi.agent.Connect.pendingRedirect ?: return@LaunchedEffect
        app.yxi.agent.Connect.pendingRedirect = null
        val st = step
        if (st is Flow.Step.Authorize && !st.code) pasted = url
    }
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
                        Text(t("%s 页面会要一个一次性码。点下面按钮：码已复制、页面已打开，粘进去按确认就行。").format(f.service.name))
                        Text(step.code, fontFamily = FontFamily.Monospace, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary)
                        Text(
                            if (f.opened) t("等 %s 那边确认…（确认完这里会自己变）").format(f.service.name) else t("码 15 分钟内有效"),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    is Flow.Step.Authorize -> {
                        Text(
                            when {
                                step.code -> t("在浏览器里登录并同意。之后页面会给你一串码 —— 复制过来粘到下面。")
                                step.forwarded -> t("在浏览器里登录并同意。同意之后页面会自己跳回来，这里会显示接上了。")
                                else -> t("在浏览器里登录并同意。同意之后浏览器会停在一个打不开的 localhost 页面 —— 把地址栏那串地址复制过来粘到下面。")
                            },
                        )
                        if (f.opened) Text(t("等浏览器那边授权…"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        OutlinedTextField(
                            pasted, { pasted = it }, Modifier.fillMaxWidth(),
                            label = { Text(if (step.code) t("把页面给的那串码粘这儿") else t("跳不回来？把 localhost 开头的地址粘这儿")) },
                            singleLine = true,
                        )
                        if (f.hint.isNotEmpty()) Text(f.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        if (f.note.isNotEmpty()) Text(f.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        if (if (step.code) pasted.isNotBlank() else pasted.startsWith("http")) {
                            TextButton(onClick = { f.paste(pasted.trim()); pasted = "" }) { Text(t("交上去")) }
                        }
                    }
                    is Flow.Step.Done -> Text(step.message, color = if (step.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            when (step) {
                is Flow.Step.Code -> TextButton(onClick = { onCopy(step.code); f.opened = true; onOpen(step.url) }) { Text(t("复制码并打开 %s").format(f.service.name)) }
                is Flow.Step.Authorize -> TextButton(onClick = { f.opened = true; onOpen(step.url) }) { Text(t("认证")) }
                is Flow.Step.Done -> TextButton(onClick = onClose) { Text(t("好")) }
                is Flow.Step.Working -> {}
            }
        },
        dismissButton = { if (step !is Flow.Step.Done) TextButton(onClick = onClose) { Text(t("关掉")) } },
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
