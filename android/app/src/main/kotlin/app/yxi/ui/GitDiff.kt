package app.yxi.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.yxi.ssh.SshSession

/** 未提交的改动。审批「让它改/提交」之前，一眼看清 Claude 到底动了什么。 */
suspend fun fetchGitDiff(ssh: SshSession?, cwd: String): String {
    val safe = cwd.replace("'", "'\\''")
    // --stat 摘要 + 具体 diff（封顶 60k，手机上够看了）。不是 git 仓库/没改动就给一句人话。
    val cmd = "cd '$safe' 2>/dev/null && " +
        "{ s=\$(git diff --stat 2>/dev/null); d=\$(git diff 2>/dev/null | head -c 60000); " +
        "if [ -z \"\$s\" ]; then echo '（没有未提交的改动）'; else printf '%s\\n\\n%s' \"\$s\" \"\$d\"; fi; } " +
        "|| echo '（这里不是 git 仓库）'"
    return ssh?.exec(cmd).orEmpty().ifBlank { "（读不到）" }
}

/** 底部弹出的 diff 查看：+ 绿 / - 红 / @@ 青，等宽、可横滑。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiffSheet(text: String?, onDismiss: () -> Unit) {
    if (text == null) return
    val green = Color(0xFF5FB570); val red = Color(0xFFCF6D6D); val cyan = Color(0xFF5AA6C0)
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp, 0.dp, 16.dp, 24.dp)) {
            Text(t("未提交的改动"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 10.dp))
            Column(
                Modifier.fillMaxWidth().heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()),
            ) {
                text.lineSequence().forEach { line ->
                    val c = when {
                        line.startsWith("+") && !line.startsWith("+++") -> green
                        line.startsWith("-") && !line.startsWith("---") -> red
                        line.startsWith("@@") -> cyan
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text(line.ifEmpty { " " }, style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp), color = c, softWrap = false)
                }
            }
        }
    }
}
