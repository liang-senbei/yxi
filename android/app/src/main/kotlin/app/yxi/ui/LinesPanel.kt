package app.yxi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.yxi.agent.Lines
import app.yxi.agent.Session
import app.yxi.agent.SessionState
import app.yxi.ssh.Host
import app.yxi.ssh.SshSession
import app.yxi.ui.theme.Copper
import app.yxi.ui.theme.Muted
import kotlinx.coroutines.launch

/**
 * **线路** —— 这台机器上的 Claude Code 走哪个端点、用哪把钥匙。
 * 取数与写入在 [Lines]，那里写着为什么「三个 key 必须全写、不设写空串」。
 *
 * ⚠️ **换线路不用重启**（实测，见 [Lines] 顶上那段）。所以这一页的默认动作就是「点一下就换」，
 * 不做二级确认 —— 换线是可逆的、纯配置的动作。
 *
 * ⚠️ **忙的时候不立刻切**：那会让一轮对话前半段走 A、后半段走 B。
 * 缓存是按端点分的，中途换端点要把整段对话重读一遍（慢且贵）；新线路还不一定有同一个模型，
 * 半途报错的话人会以为是任务本身失败了。所以**干活中就排队，空闲了自动落地**。
 * 状态直接用父级传进来的 [sessions]（[SessionProbe] 已经探过一次），**不另起一次 SSH 往返**。
 *
 * ⚠️ **Codex 不吃这一套**（它读 `~/.codex/auth.json`，不是 env），这一页只管 Claude Code。
 * 机器上有 Codex 会话时明说一句，别让人以为一起换了。
 */
@Composable
fun LinesPanel(ssh: SshSession?, host: Host?, sessions: List<Session>) {
    val scope = rememberCoroutineScope()
    val key = host?.id
    var lines by remember(key) { mutableStateOf<List<Lines.Line>?>(null) }
    var env by remember(key) { mutableStateOf<Lines.Env?>(null) }
    var loading by remember(key) { mutableStateOf(true) }
    var note by remember(key) { mutableStateOf("") }
    /** 排队中的那条：忙完自动落地。null = 没有排队。 */
    var pending by remember(key) { mutableStateOf<Lines.Line?>(null) }
    var pendingDefault by remember(key) { mutableStateOf(false) }
    var edit by remember(key) { mutableStateOf<Lines.Line?>(null) }

    val busy = sessions.count { it.state == SessionState.Working && !it.isCodex }
    val hasCodex = sessions.any { it.isCodex }

    suspend fun reload() {
        lines = Lines.list(ssh)
        env = Lines.current(ssh)
    }

    LaunchedEffect(ssh, key) {
        loading = true
        reload()
        loading = false
    }

    suspend fun doApply(line: Lines.Line?) {
        val err = Lines.apply(ssh, line)
        if (err != null) { note = t("没换成：%s").format(err); return }
        reload()
        val probe = Lines.probe(ssh, line?.baseUrl.orEmpty())
        // ⚠️ 换端点之后第一次请求会**全量重读上下文**（缓存是按端点分的），又慢又贵。
        //    不说的话人会以为切换卡住了 —— 这是 cc-remote-dev-station 提醒的一条。
        note = t("已换到「%s」· %s\n下一次请求就走新线路；那一次会重读整段对话，慢一点、贵一点，是正常的。")
            .format(line?.name ?: t("默认"), probe)
    }

    // 排队中的那条：一旦没有会话在干活，自动落地
    LaunchedEffect(busy, pending, pendingDefault) {
        if (busy == 0 && (pending != null || pendingDefault)) {
            val target = pending
            pending = null; pendingDefault = false
            doApply(target)
        }
    }

    fun pick(line: Lines.Line?) {
        if (busy > 0) {
            pending = line; pendingDefault = line == null
            note = t("这台机器上有 %d 个会话在干活 —— 已排队，跑完这一轮自动换。").format(busy)
        } else scope.launch { doApply(line) }
    }

    edit?.let { e ->
        LineEditor(
            line = e,
            onDismiss = { edit = null },
            onSave = { saved ->
                edit = null
                scope.launch {
                    val cur = lines.orEmpty()
                    val next = if (cur.any { it.id == saved.id })
                        cur.map { if (it.id == saved.id) saved else it } else cur + saved
                    note = Lines.saveList(ssh, next)?.let { t("没存上：%s").format(it) }.orEmpty()
                    reload()
                }
            },
            onDelete = {
                edit = null
                scope.launch {
                    note = Lines.saveList(ssh, lines.orEmpty().filter { it.id != e.id })
                        ?.let { t("没删掉：%s").format(it) }.orEmpty()
                    reload()
                }
            },
        )
    }

    val cur = env
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(14.dp, 4.dp, 14.dp, 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (note.isNotBlank()) item { Banner(note, Copper) { note = "" } }
            if (pending != null || pendingDefault) item {
                // ⚠️ 这里**不能用 Amber** —— STYLE.md §1.2 把它定死给「需要你动手」，
                //    排队中是状态通知，不需要你做任何事。挪用会稀释那个色的含义。
                Banner(t("排队中：这一轮跑完就换到「%s」").format(pending?.name ?: t("默认")),
                    MaterialTheme.colorScheme.tertiary) {
                    pending = null; pendingDefault = false
                }
            }
            if (hasCodex) item {
                // 「拿不到」和「不适用」是两件事，这里是后者：Codex 根本不看这些 env
                Hint(t("这台机器上有 Codex 会话 —— 它读的是自己那份配置，不跟着线路走，换了要重开。"))
            }

            when {
                loading && lines == null -> item { Hint(t("正在取…")) }
                // 拿不到 ≠ 一条都没建过
                lines == null -> item { Hint(t("取不到 —— 没连上那台机器。")) }
                else -> {
                    item {
                        Text(
                            t("当前"), Modifier.padding(4.dp, 8.dp, 0.dp, 2.dp),
                            style = MaterialTheme.typography.labelLarge, color = Muted,
                        )
                    }
                    // 「默认」永远在列、永远可选 —— 那是退路：任何一条线路出问题都能一键回来
                    item {
                        LineRow(
                            name = t("默认（走 Claude Code 自己的登录）"),
                            sub = t("不设任何端点和钥匙"),
                            current = cur?.isDefault == true,
                            onPick = { pick(null) },
                            onEdit = null,
                        )
                    }
                    items(lines.orEmpty().size) { i ->
                        val l = lines.orEmpty()[i]
                        LineRow(
                            name = l.name.ifBlank { l.id },
                            sub = listOfNotNull(
                                l.baseUrl.ifBlank { null },
                                Lines.mask(l.token).ifBlank { null }?.let { t("令牌 ") + it },
                                Lines.mask(l.apiKey).ifBlank { null }?.let { t("密钥 ") + it },
                            ).joinToString(" · ").ifBlank { t("什么都没填") },
                            current = cur != null && Lines.matches(l, cur),
                            onPick = { pick(l) },
                            onEdit = { edit = l },
                        )
                    }
                    if (cur != null && !cur.isDefault && lines.orEmpty().none { Lines.matches(it, cur) }) item {
                        // 真相源是 settings.json，不是我们的清单 —— 对不上就照实说，别假装是列表里某条
                        Hint(t("现在走的不是列表里的任何一条（可能是在电脑上或手动改的）：%s")
                            .format(cur.baseUrl.ifBlank { t("只设了钥匙，没设端点") }))
                    }
                    item {
                        AddRow { edit = Lines.Line(id = Lines.newId(), name = "") }
                    }
                }
            }
        }
    }
}

@Composable
private fun LineRow(
    name: String,
    sub: String,
    current: Boolean,
    onPick: () -> Unit,
    onEdit: (() -> Unit)?,
) {
    val shape = RoundedCornerShape(16.dp)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = shape,
        modifier = Modifier.fillMaxWidth().clip(shape).clickable(onClick = onPick)
            .then(if (current) Modifier.border(1.5.dp, Copper, shape) else Modifier),
    ) {
        Row(
            Modifier.padding(14.dp, 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier.size(8.dp).clip(CircleShape)
                    .background(if (current) Copper else MaterialTheme.colorScheme.outlineVariant),
            )
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    sub, style = MaterialTheme.typography.labelSmall, color = Muted,
                    fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (current) Text(t("在用"), style = MaterialTheme.typography.labelSmall, color = Copper)
            onEdit?.let {
                Text(
                    t("改"), Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = it).padding(8.dp, 4.dp),
                    style = MaterialTheme.typography.labelMedium, color = Muted,
                )
            }
        }
    }
}

@Composable
private fun AddRow(onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick),
    ) {
        Text(
            t("+ 加一条线路"), Modifier.fillMaxWidth().padding(16.dp),
            style = MaterialTheme.typography.labelLarge, color = Muted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/**
 * 改一条线路。
 * ⚠️ 钥匙这里显示**原文**（要改就得看得见），列表上才打码 —— 看原文是明确动作。
 */
@Composable
private fun LineEditor(
    line: Lines.Line,
    onDismiss: () -> Unit,
    onSave: (Lines.Line) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember(line.id) { mutableStateOf(line.name) }
    var url by remember(line.id) { mutableStateOf(line.baseUrl) }
    var token by remember(line.id) { mutableStateOf(line.token) }
    var key by remember(line.id) { mutableStateOf(line.apiKey) }
    var confirmDel by remember(line.id) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (line.name.isBlank()) t("加一条线路") else t("改线路")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(t("名字（自己认得就行）")) }, singleLine = true)
                OutlinedTextField(url, { url = it }, label = { Text(t("端点 ANTHROPIC_BASE_URL")) }, singleLine = true)
                OutlinedTextField(token, { token = it }, label = { Text(t("令牌 ANTHROPIC_AUTH_TOKEN")) }, singleLine = true)
                OutlinedTextField(key, { key = it }, label = { Text(t("密钥 ANTHROPIC_API_KEY")) }, singleLine = true)
                Text(
                    t("留空的那项会写成空串 —— 那才是「不设」。留空不等于沿用上一条。"),
                    style = MaterialTheme.typography.labelSmall, color = Muted,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(line.copy(name = name.trim(), baseUrl = url.trim(), token = token.trim(), apiKey = key.trim()))
            }) { Text(t("存")) }
        },
        dismissButton = {
            Row {
                // ⚠️ **删要多一步**（STYLE.md §0：危险动作永远多一步）。线路删了钥匙就没了，
                //    要重新去供应商那儿取一遍再手打进来 —— 不可逆，不能由一次误触独自决定。
                if (line.name.isNotBlank()) TextButton(onClick = { if (confirmDel) onDelete() else confirmDel = true }) {
                    Text(if (confirmDel) t("真删？再点一次") else t("删掉"), color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onDismiss) { Text(t("算了")) }
            }
        },
    )
}

@Composable
private fun Banner(text: String, tint: androidx.compose.ui.graphics.Color, onClose: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, tint, RoundedCornerShape(16.dp)),
    ) {
        Row(Modifier.padding(14.dp, 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            Text(
                t("知道了"), Modifier.clickable(onClick = onClose),
                style = MaterialTheme.typography.labelSmall, color = tint,
            )
        }
    }
}

@Composable
private fun Hint(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text, Modifier.padding(16.dp, 14.dp), style = MaterialTheme.typography.bodySmall, color = Muted)
    }
}
