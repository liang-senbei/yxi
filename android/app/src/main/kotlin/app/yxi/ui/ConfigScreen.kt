package app.yxi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.yxi.agent.ConfigRemote
import app.yxi.ssh.Host
import app.yxi.ssh.SshSession
import com.mikepenz.markdown.m3.Markdown
import kotlinx.coroutines.launch

private val Pill = RoundedCornerShape(100.dp)
private val Mono = FontFamily.Monospace

/**
 * **配置** —— 分服务器、分工具（Claude Code / Codex）浏览并编辑那台机器上的 agent 配置：
 * 技能 / MCP / 子 agent / 命令 / 权限 / 钩子 / 记忆 / 插件。哪台装了哪个工具才显示哪个。
 *
 * ⚠️ 密钥只在浏览时打码；编辑是明确动作，取原文、保存前**先备份、json 先校验**（见 [ConfigRemote]）。
 * 走的就是 App 那条 SSH 连接，不碰公网。
 */
@Composable
fun ConfigScreen(
    ssh: SshSession?,
    host: Host,
    hosts: List<Host> = listOf(host),
    onPickHost: (Host) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var tools by remember(host.id) { mutableStateOf<List<ConfigRemote.Tool>?>(null) }
    var open by remember(host.id) { mutableStateOf<ConfigRemote.Item?>(null) }
    val expanded = remember(host.id) { mutableStateListOf<String>() }
    var panel by rememberSaveable { mutableStateOf("connect") }

    LaunchedEffect(ssh, host.id) {
        tools = null
        tools = ConfigRemote.load(ssh)
    }

    open?.let { item ->
        ConfigDetail(ssh, item, onBack = { open = null }, modifier = modifier)
        return
    }

    Column(modifier.fillMaxSize()) {
        // 主机头 + 下拉（跟看板一致）
        Row(Modifier.fillMaxWidth().padding(18.dp, 14.dp, 18.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
            var menu by remember { mutableStateOf(false) }
            Column(Modifier.weight(1f)) {
                Row(
                    Modifier.clickable(enabled = hosts.size > 1) { menu = true },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(hostColor(host.id)))
                    Text(host.alias, style = MaterialTheme.typography.headlineSmall)
                    if (hosts.size > 1) Text("▾", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.outline)
                }
                DropdownMenu(menu, { menu = false }) {
                    hosts.forEach { h ->
                        DropdownMenuItem(text = { Text(h.alias + if (h.id == host.id) "  ✓" else "") },
                            onClick = { menu = false; onPickHost(h) })
                    }
                }
                Text(t("这台机器上的 agent 配置"), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline)
            }
        }

        // 两块：「连接」（把第三方服务接给 agent）和「Agent 配置」（原来那棵配置树）
        Row(Modifier.padding(14.dp, 0.dp, 14.dp, 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("connect" to t("连接"), "agent" to t("Agent 配置")).forEach { (k, label) ->
                val on = panel == k
                Text(
                    label,
                    Modifier.clip(Pill)
                        .background(if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable { panel = k }
                        .padding(16.dp, 9.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (panel == "connect") { ConnectPanel(ssh, host); return@Column }

        val ts = tools
        when {
            ts == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(30.dp), strokeWidth = 2.5.dp)
            }
            ts.all { it.cats.isEmpty() } -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(t("这台机器上没找到配置"), style = MaterialTheme.typography.titleMedium)
                    Text(t("装了 Claude Code（~/.claude）或 Codex（~/.codex）才有"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            }
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp, 4.dp, 14.dp, 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ts.forEach { tool ->
                    if (tool.cats.isEmpty()) return@forEach
                    item(key = "tool-${tool.key}") {
                        Text(tool.name, Modifier.padding(4.dp, 10.dp, 4.dp, 2.dp),
                            style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    tool.cats.forEach { cat ->
                        val ck = "${tool.key}/${cat.key}"
                        item(key = "cat-$ck") {
                            CategoryRow(cat, ck in expanded) {
                                if (ck in expanded) expanded.remove(ck) else expanded.add(ck)
                            }
                        }
                        if (ck in expanded) items(cat.items, key = { "$ck/${it.title}/${it.path}" }) { it2 ->
                            ItemRow(it2) { open = it2 }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryRow(cat: ConfigRemote.Cat, open: Boolean, onToggle: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).clickable(onClick = onToggle)) {
        Row(Modifier.padding(16.dp, 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(cat.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Text("${cat.items.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.width(8.dp))
            Text(if (open) "▾" else "▸", color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun ItemRow(item: ConfigRemote.Item, onOpen: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp).clip(MaterialTheme.shapes.medium).clickable(onClick = onOpen)) {
        Row(Modifier.padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.bodyLarge,
                    fontFamily = if (item.path.isNotBlank() || item.structured) Mono else null)
                if (item.sub.isNotBlank()) Text(item.sub, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline, maxLines = 1)
            }
            Text("›", color = MaterialTheme.colorScheme.outline)
        }
    }
}

/** 单个配置项的查看/编辑。文件类可编辑：点「编辑」取原文 → 改 → 保存(先备份、json 先校验)。 */
@Composable
private fun ConfigDetail(ssh: SshSession?, item: ConfigRemote.Item, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var content by remember(item) { mutableStateOf<String?>(null) }
    var editing by remember(item) { mutableStateOf(false) }
    var draft by remember(item) { mutableStateOf("") }
    var busy by remember(item) { mutableStateOf(false) }
    val canEdit = item.path.isNotBlank()

    LaunchedEffect(item) {
        content = when {
            item.inline.isNotBlank() -> item.inline
            item.path.isNotBlank() -> ConfigRemote.readFile(ssh, item.path) ?: t("读不到")
            else -> ""
        }
    }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(14.dp, 12.dp, 14.dp, 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = Pill,
                modifier = Modifier.clip(Pill).clickable(onClick = onBack)) {
                Text("←", Modifier.padding(15.dp, 8.dp), style = MaterialTheme.typography.titleSmall)
            }
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                if (item.path.isNotBlank()) Text(item.path, style = MaterialTheme.typography.labelSmall.copy(fontFamily = Mono),
                    color = MaterialTheme.colorScheme.outline, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            if (canEdit && !editing) EditPill(t("编辑")) {
                busy = true
                scope.launch {
                    // 编辑取**原文**（不打码）—— settings 里的密钥这时会明文可见，横幅提醒
                    draft = ConfigRemote.readFile(ssh, item.path) ?: ""
                    busy = false; editing = true
                }
            }
            if (canEdit && editing) EditPill(if (busy) t("保存中…") else t("保存"), enabled = !busy) {
                busy = true
                scope.launch {
                    val err = ConfigRemote.save(ssh, item.path, draft)
                    busy = false
                    if (err == null) {
                        editing = false; content = draft
                        android.widget.Toast.makeText(ctx, t("已保存（旧版已备份）"), android.widget.Toast.LENGTH_SHORT).show()
                    } else android.widget.Toast.makeText(ctx, err, android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }

        if (editing && item.structured) Text(
            t("⚠️ 这是结构化配置，改坏语法会让工具起不来；保存前会自动备份、json 会先校验。密钥此刻明文可见，当心别人看屏。"),
            Modifier.fillMaxWidth().padding(18.dp, 0.dp, 18.dp, 6.dp),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error,
        )

        val c = content
        Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp, 4.dp, 16.dp, 16.dp)) {
            when {
                c == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp)
                }
                editing -> BasicTextField(
                    draft, { draft = it },
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    textStyle = TextStyle(fontFamily = Mono, fontSize = 13.sp, lineHeight = 19.sp,
                        color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                )
                item.path.endsWith(".md") -> Column(Modifier.verticalScroll(rememberScrollState())) {
                    Markdown(c, modifier = Modifier.fillMaxWidth())
                }
                else -> Text(c, Modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono, fontSize = 12.sp, lineHeight = 17.sp),
                    softWrap = false)
            }
        }
    }
}

@Composable
private fun EditPill(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = Pill,
        modifier = Modifier.clip(Pill).clickable(enabled = enabled, onClick = onClick)) {
        Text(label, Modifier.padding(16.dp, 8.dp), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}
