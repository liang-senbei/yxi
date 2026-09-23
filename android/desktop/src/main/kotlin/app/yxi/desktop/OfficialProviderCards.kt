package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp

@Composable internal fun OfficialProviderCards(engine: String) {
    val uris = LocalUriHandler.current
    OfficialProviderProfiles.forEngine(engine).forEachIndexed { index, profile ->
        OutlinedCard(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RunnerBrandIcon(engine, Modifier.size(22.dp))
                    Text(profile.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Text(if (index == 0) "内置默认选项" else "内置选项", style = MaterialTheme.typography.labelSmall, color = Tokens.current.textMuted)
                }
                Text(profile.description, style = MaterialTheme.typography.bodySmall)
                Text("账号与实际线路尚未核对；此卡片不会自动覆盖已有配置。", style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
                TextButton({ uris.openUri(profile.documentation) }) { Text("官方登录与使用说明") }
            }
        }
    }
}
