package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.yxi.agent.Lines

@Composable
internal fun AgentProfileChoices(engine: String, profileId: String, profiles: List<Lines.Line>, loading: Boolean, busy: Boolean,
    onEngine: (String) -> Unit, onProfile: (String) -> Unit) {
    Text("1  选择运行器", style = MaterialTheme.typography.titleSmall)
    configurationEngines.chunked(2).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { (id, label) ->
                QuietChoice(engine == id, { onEngine(id) }, enabled = !loading && !busy, modifier = Modifier.weight(1f),
                    label = { Text(label) }, leadingIcon = { RunnerBrandIcon(id, Modifier.size(20.dp)) })
            }
        }
    }
    HorizontalDivider()
    Text("2  选择已保存配置", style = MaterialTheme.typography.titleSmall)
    val matching = profiles.filter { it.agent == engine }
    if (engine !in listOf(Lines.CLAUDE, Lines.CODEX)) {
        Text("该运行器的独立配置适配尚未完成，暂不能保存。", color = Tokens.current.textMuted)
    } else {
        matching.forEach { profile ->
            OutlinedCard(onClick = { onProfile(profile.id) }, enabled = !busy && !loading) {
                Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(profileId == profile.id, null)
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(profile.name)
                        Text(profile.baseUrl, style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
                    }
                }
            }
        }
        if (matching.isEmpty() && !loading) Text("此运行器还没有已保存配置，请先在配置页添加。", color = Tokens.current.textMuted)
        if (profileId.isNotBlank() && matching.none { it.id == profileId })
            Text("原配置已删除，请重新选择。", color = Tokens.current.danger)
    }
}
