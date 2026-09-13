package app.yxi.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.yxi.agent.ConfigRemote
import kotlinx.coroutines.launch

/**
 * 「配置」页 —— 照手机的 `ConfigScreen`：技能 / MCP / 子 agent / 命令 / 权限 / 钩子 / 记忆 / 插件。
 *
 * 数据全走 core 的 [ConfigRemote]（一条命令把整棵树抓回来），所以这一页跟手机端**永远一致** ——
 * 服务器上加了一类配置，两边同时就有了。
 *
 * ⚠️ **配置是「哪台机器的」**：它读的是当前主机上 `~/.claude` / `~/.codex` 里的东西，
 * 不是这个客户端的设置（那是 Ctrl+, 的「设置」）。所以没选主机时这一页什么都做不了，
 * 要把话说清楚，别摆一个空列表让人以为「我没配过东西」。
 */
@Composable
fun ConfigPane(state: AppState) {
    val t = Tokens.current
    val scope = rememberCoroutineScope()
    val conn = state.conn

    var tools by remember(conn) { mutableStateOf<List<ConfigRemote.Tool>>(emptyList()) }
    var busy by remember(conn) { mutableStateOf(true) }
    var picked by remember(conn) { mutableStateOf<ConfigRemote.Item?>(null) }
    var showPlugins by remember(conn) { mutableStateOf(false) }
    var showCatalog by remember(conn) { mutableStateOf(false) }

    LaunchedEffect(conn) {
        busy = true
        tools = if (conn == null) emptyList() else ConfigRemote.load(conn.ssh)
        busy = false
    }

    if (conn == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("先在左边连一台主机", style = MaterialTheme.typography.titleSmall, color = t.textPrimary)
                Text(
                    "这一页看的是那台机器上 Claude Code / Codex 的配置（技能、MCP、权限、钩子…），不是这个客户端的设置。",
                    Modifier.padding(top = 6.dp, start = 40.dp, end = 40.dp),
                    style = MaterialTheme.typography.bodySmall, color = t.textMuted,
                )
            }
        }
        return
    }

    Row(Modifier.fillMaxSize().background(t.surface0)) {
        Column(Modifier.width(300.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
            TextButton({ showPlugins = !showPlugins; showCatalog = false }) { Text(if (showPlugins) "返回配置浏览" else "主机插件 · 查看状态") }
            TextButton({ showCatalog = true; showPlugins = false }) { Text("浏览插件目录") }
            Text(
                conn.host.label,
                Modifier.padding(14.dp, 10.dp, 14.dp, 4.dp),
                style = MaterialTheme.typography.titleSmall, color = t.textPrimary, maxLines = 1,
            )
            when {
                busy -> Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                // ⚠️ 「读不到」和「什么都没配」要分开说：前者多半是没装 Claude Code，后者是真空
                tools.isEmpty() -> Text(
                    "没读到配置 —— 这台机器上可能没装 Claude Code / Codex，或者家目录里还没有 .claude。",
                    Modifier.padding(14.dp, 4.dp), style = MaterialTheme.typography.bodySmall, color = t.textMuted,
                )
                else -> tools.forEach { tool ->
                    Text(
                        tool.name,
                        Modifier.padding(14.dp, 12.dp, 14.dp, 2.dp),
                        style = MaterialTheme.typography.labelLarge, color = t.textSecondary,
                    )
                    tool.cats.forEach { cat ->
                        Text(
                            cat.title,
                            Modifier.padding(18.dp, 6.dp, 14.dp, 2.dp),
                            style = MaterialTheme.typography.labelMedium, color = t.textMuted,
                        )
                        cat.items.forEach { item ->
                            ItemRow(item, selected = picked === item && !showPlugins && !showCatalog) { picked = item; showPlugins = false; showCatalog = false }
                        }
                    }
                }
            }
        }
        VerticalDivider(color = t.border)
        Box(Modifier.fillMaxSize()) {
            val p = picked
            if (showCatalog) PluginCatalogPane(conn)
            else if (showPlugins) PluginInventoryPane(state, conn)
            else if (p == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("左边选一项", style = MaterialTheme.typography.bodyMedium, color = t.textMuted)
            } else ItemDetail(conn, p)
        }
    }
}

@Composable
private fun ItemRow(item: ConfigRemote.Item, selected: Boolean, onClick: () -> Unit) {
    val t = Tokens.current
    Column(
        Modifier.fillMaxWidth()
            .background(if (selected) t.surface2 else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(22.dp, 6.dp, 12.dp, 6.dp),
    ) {
        Text(item.title, style = MaterialTheme.typography.bodyMedium, color = t.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (item.sub.isNotBlank()) Text(item.sub, style = MaterialTheme.typography.bodySmall, color = t.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * 一项的内容：有文件的就把文件读出来，可以改、可以存；没有文件的（内联那种）直接摆出来。
 *
 * ⚠️ **存盘失败必须说出来。** [ConfigRemote.save] 返回的是「错在哪」——
 * 结构化文件（settings.json）它会先校验再写，写坏了 Claude Code 下次直接起不来，
 * 所以这里宁可挡住也不能悄悄存进去。
 */
@Composable
private fun ItemDetail(conn: Conn, item: ConfigRemote.Item) {
    val t = Tokens.current
    val scope = rememberCoroutineScope()
    var text by remember(item) { mutableStateOf(item.inline) }
    var loading by remember(item) { mutableStateOf(item.path.isNotBlank()) }
    var dirty by remember(item) { mutableStateOf(false) }
    var note by remember(item) { mutableStateOf("") }
    var saving by remember(item) { mutableStateOf(false) }

    LaunchedEffect(item) {
        if (item.path.isBlank()) return@LaunchedEffect
        loading = true
        text = ConfigRemote.readFile(conn.ssh, item.path) ?: ""
        loading = false
        dirty = false
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(14.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleSmall, color = t.textPrimary, maxLines = 1)
                if (item.path.isNotBlank()) Text(
                    item.path,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = t.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (item.path.isNotBlank()) TextButton(
                enabled = dirty && !saving,
                onClick = {
                    scope.launch {
                        saving = true
                        val err = ConfigRemote.save(conn.ssh, item.path, text)
                        saving = false
                        if (err == null) { dirty = false; note = "已保存" } else note = err
                    }
                },
            ) { Text(if (saving) "保存中…" else "保存") }
        }
        if (note.isNotBlank()) Text(note, Modifier.padding(14.dp, 0.dp, 14.dp, 4.dp), style = MaterialTheme.typography.bodySmall, color = if (note == "已保存") t.textMuted else t.danger)
        HorizontalDivider(color = t.border)
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            item.path.isBlank() -> Text(
                text.ifBlank { "（这一项没有内容）" },
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = t.textPrimary,
            )
            else -> BasicTextField(
                value = text,
                onValueChange = { text = it; dirty = true; note = "" },
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = MaterialTheme.typography.bodySmall.fontSize, color = t.textPrimary),
                cursorBrush = SolidColor(t.accent),
            )
        }
        Spacer(Modifier.size(0.dp))
    }
}
