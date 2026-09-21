package app.yxi.desktop

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.yxi.agent.Lines

internal val configurationEngines = listOf(
    Lines.CLAUDE to "Claude Code", Lines.CODEX to "Codex", "opencode" to "OpenCode",
    "gemini" to "Gemini", "grok" to "Grok Build", "hermes" to "Hermes",
)

@Composable
internal fun ConfigurationHeader(state: AppState, conn: Conn, engine: String, busy: Boolean,
                                 catalogReady: Boolean, selectEngine: (String) -> Unit, add: () -> Unit) {
    var hostsOpen by remember { mutableStateOf(false) }
    val hosts = remember(state.conns.map { it.host }) { (state.conns.map { it.host } + Store.hosts()).distinctBy { it.id } }
    NativeOverlay(hostsOpen)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 950.dp
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box {
                    TextButton({ hostsOpen = true }, enabled = !busy) {
                        Text(conn.host.label + " ⌄", style = MaterialTheme.typography.headlineSmall, color = Tokens.current.textPrimary)
                    }
                    DropdownMenu(hostsOpen, { hostsOpen = false }) {
                        Text("配置目标服务器", Modifier.padding(16.dp, 8.dp), style = MaterialTheme.typography.labelMedium, color = Tokens.current.textMuted)
                        hosts.forEach { host ->
                            val connected = state.conns.any { it.host.id == host.id && it.ssh.isConnected }
                            DropdownMenuItem(text = { Text(host.label + if (connected) "" else " · 请先连接") },
                                enabled = connected, trailingIcon = { if (conn.host.id == host.id) Text("✓") },
                                onClick = { state.configurationHostId = host.id; hostsOpen = false })
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                if (!compact) EngineSwitch(engine, busy, selectEngine)
                TextButton({ state.page = Page.ConfigFiles }, enabled = !busy) { Text("配置文件") }
                FilledTonalButton(add, enabled = !busy && catalogReady && engine in listOf(Lines.CLAUDE, Lines.CODEX)) { Text("＋ 添加") }
            }
            if (compact) EngineSwitch(engine, busy, selectEngine)
            Text("供应商配置 · ${conn.host.username}@${conn.host.hostname}", style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
        }
    }
}

@Composable
private fun EngineSwitch(engine: String, busy: Boolean, select: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        configurationEngines.forEach { (id, label) ->
            FilterChip(engine == id, { select(id) }, enabled = !busy, label = { Text(label) })
        }
    }
}
