package app.yxi.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.yxi.ssh.Host
import app.yxi.ssh.HostStore
import app.yxi.ssh.KeyManager
import app.yxi.ssh.Paths
import app.yxi.ssh.Sftp
import app.yxi.ssh.SshSession
import app.yxi.ui.theme.*
import kotlinx.coroutines.launch

private val Pill = RoundedCornerShape(100.dp)
private val Mono = FontFamily.Monospace

/**
 * 文件模式：在任意 SSH 主机上翻文件。
 *
 * ⚠️ **走 SFTP，服务器上不用装任何东西**（PRD 附录 H）。起点是会话的 cwd —— 
 * 你正在看的那个会话在哪儿干活，文件模式就从哪儿开始，不用自己找路。
 *
 * **只读。** 不做写和删：手机上误触的代价太高，而这个功能的价值是「看一眼」。
 */
@Composable
fun FilesScreen(
    /** ⚠️ 由 [Workspace] 持有 —— 切模式时不该重开通道 */
    sftp: Sftp?,
    startDir: String,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var dir by remember { mutableStateOf(startDir) }
    var entries by remember { mutableStateOf<List<Sftp.Entry>>(emptyList()) }
    var status by remember { mutableStateOf<String?>("连接中…") }
    var open by remember { mutableStateOf<String?>(null) }   // 正在看的文件
    var jumping by remember { mutableStateOf(false) }
    // 最近去过的目录。⚠️ 只在内存里 —— 关掉就没了。要跨会话记住得落盘，那是另一件事
    val recent = remember { mutableStateListOf<String>() }

    LaunchedEffect(sftp) {
        val s = sftp ?: return@LaunchedEffect
        // 起点可能是 `~` 或不存在的路径 —— 解析失败就退到家目录，别把界面卡死
        dir = runCatching { s.realpath(startDir) }.getOrElse {
            runCatching { s.realpath(".") }.getOrDefault("/")
        }
    }

    LaunchedEffect(dir, sftp) {
        val s = sftp ?: return@LaunchedEffect
        status = null
        runCatching { s.list(dir) }
            .onSuccess {
                entries = it
                recent.remove(dir); recent.add(0, dir)
                while (recent.size > 8) recent.removeAt(recent.lastIndex)
            }
            .onFailure { entries = emptyList(); status = Sftp.explain(it) }
    }

    open?.let { file ->
        // ⚠️ 要把 modifier 传下去 —— 里面有 Scaffold 的系统栏边距，漏了标题会被状态栏压住
        FileViewer(sftp, file, onBack = { open = null }, modifier = modifier)
        return
    }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(18.dp, 8.dp, 18.dp, 4.dp)) {
            Spacer(Modifier.weight(1f))
            Text(
                status ?: "${entries.size} 项",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                color = Dim,
            )
        }

        // 面包屑：点哪一级跳哪一级
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(14.dp, 0.dp, 14.dp, 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Paths.crumbs(dir).forEachIndexed { i, (label, target) ->
                if (i > 0) Text("/", style = MaterialTheme.typography.labelSmall, color = Dim)
                Surface(
                    color = if (target == dir) SurfaceContainerHigh else SurfaceContainer,
                    shape = Pill,
                    modifier = Modifier.clickable { dir = target },
                ) {
                    Text(
                        label, Modifier.padding(11.dp, 6.dp),
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = Mono),
                        color = if (target == dir) OnSurface else Muted,
                    )
                }
            }
            // ⚠️ 必须有：/tmp 这种目录随便就是几百项，靠滚是找不到东西的
            Surface(color = SurfaceContainer, shape = Pill, modifier = Modifier.clickable { jumping = true }) {
                Text(
                    "⌖", Modifier.padding(12.dp, 6.dp),
                    style = MaterialTheme.typography.labelMedium, color = Copper,
                )
            }
        }

        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 20.dp)) {
            if (dir != "/") item(key = "..") {
                Row(
                    Modifier.fillMaxWidth().clickable { dir = Paths.dirOf(dir) }.padding(20.dp, 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text("↰", style = MaterialTheme.typography.titleMedium, color = Copper)
                    Text("上一级", style = MaterialTheme.typography.bodyLarge, color = Muted)
                }
            }
            items(entries.size, key = { entries[it].name }) { i ->
                val e = entries[i]
                Row(
                    Modifier.fillMaxWidth()
                        .clickable {
                            if (e.isDir) dir = Paths.resolve(dir, e.name) else open = Paths.resolve(dir, e.name)
                        }
                        .padding(20.dp, 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        if (e.isDir) "▸" else "·",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (e.isDir) Copper else Dim,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            e.name + if (e.isLink) " ⇢" else "",
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (e.isDir) OnSurface else OnSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    if (!e.isDir) {
                        Text(
                            human(e.size),
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = Mono),
                            color = Dim,
                        )
                    }
                }
            }
            if (entries.isEmpty() && status == null) item("empty") {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    Text("空目录", style = MaterialTheme.typography.bodyMedium, color = Dim)
                }
            }
        }
    }
    if (jumping) {
        JumpDialog(
            current = dir,
            recent = recent.toList(),
            onGo = { target ->
                jumping = false
                scope.launch {
                    val s = sftp ?: return@launch
                    // 输错了要说清楚，不能默默不动
                    runCatching { s.realpath(target) }
                        .onSuccess { abs -> if (s.isDir(abs)) dir = abs else open = abs }
                        .onFailure { status = "去不了 $target：" + Sftp.explain(it) }
                }
            },
            onDismiss = { jumping = false },
        )
    }
}

internal fun human(n: Long): String = when {
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> "%.0f K".format(n / 1024.0)
    n < 1024L * 1024 * 1024 -> "%.1f M".format(n / 1024.0 / 1024)
    else -> "%.1f G".format(n / 1024.0 / 1024 / 1024)
}

/** 直接输入路径 + 最近去过的。**收藏没做** —— 那要落盘，等有真需求再说。 */
@Composable
private fun JumpDialog(
    current: String,
    recent: List<String>,
    onGo: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("去哪儿") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    text, { text = it },
                    placeholder = { Text("/opt/workspace 或 ~/src") },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono),
                    shape = MaterialTheme.shapes.medium,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (recent.size > 1) {
                    Text("最近", style = MaterialTheme.typography.labelSmall, color = Dim)
                    recent.drop(1).take(5).forEach { r ->
                        Text(
                            r,
                            Modifier.fillMaxWidth().clickable { onGo(r) }.padding(vertical = 6.dp),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                            color = Muted, maxLines = 1,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton({ onGo(text.trim()) }) { Text("去") } },
        dismissButton = { TextButton(onDismiss) { Text("取消") } },
    )
}
