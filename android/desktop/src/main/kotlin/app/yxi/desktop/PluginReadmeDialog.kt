package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.yxi.ssh.Shell
import com.mikepenz.markdown.m3.Markdown
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

@Composable
internal fun PluginReadmeDialog(conn: Conn, plugin: InstalledPlugin, close: () -> Unit) {
    var content by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf("") }
    var revision by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(true) }
    LaunchedEffect(conn, plugin, revision) {
        busy = true; error = ""
        try {
            val script = """
import pathlib, json, sys
root = pathlib.Path(sys.argv[1])
if not root.is_absolute() or not root.is_dir():
    raise ValueError('安装目录不可读取')
def read(path):
    with path.open('rb') as f:
        data = f.read(131073)
    return data[:131072].decode('utf-8', errors='replace'), len(data) > 131072
result = {'description': '', 'readme': '', 'file': '', 'truncated': False}
manifest = root / '.claude-plugin' / 'plugin.json'
if manifest.is_file():
    text, truncated = read(manifest)
    if not truncated:
        try:
            value = json.loads(text).get('description', '')
            if isinstance(value, str): result['description'] = value[:8000]
        except (ValueError, AttributeError): pass
for name in ['README.md', 'readme.md', 'Readme.md', 'README.MD', 'README.txt', 'README']:
    path = root / name
    if path.is_file():
        result['readme'], result['truncated'] = read(path)
        result['file'] = name
        break
print('__YXI_PLUGIN_DOC__:' + json.dumps(result, ensure_ascii=False))
""".trimIndent()
            val raw = conn.ssh.exec("python3 -c " + Shell.q(script) + " " + Shell.q(plugin.path))
            val line = raw.lineSequence().lastOrNull { it.startsWith("__YXI_PLUGIN_DOC__:") } ?: error("未能读取插件说明，请检查连接与安装目录")
            content = JSONObject(line.removePrefix("__YXI_PLUGIN_DOC__:"))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message.orEmpty() }
        finally { busy = false }
    }
    WorkbenchDialog(onDismissRequest = close, title = { Text(plugin.id.substringBefore('@')) },
        text = { Column(Modifier.widthIn(max = 640.dp).heightIn(max = 540.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${conn.host.label} · ${plugin.scope} · ${plugin.version}", style = MaterialTheme.typography.labelMedium, color = Tokens.current.textMuted)
            SelectionContainer { Text(plugin.path, style = MaterialTheme.typography.bodySmall) }
            if (plugin.project.isNotBlank()) Text("项目：${plugin.project}", style = MaterialTheme.typography.bodySmall)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (error.isNotBlank()) Text(error, color = Tokens.current.danger)
            content?.let { doc ->
                if (doc.optString("description").isNotBlank()) Text(doc.getString("description"))
                if (doc.optBoolean("truncated")) Text("说明较长，显示前 128 KiB", style = MaterialTheme.typography.labelSmall)
                if (doc.optString("readme").isBlank()) Text("安装包未提供 README 说明。", color = Tokens.current.textMuted)
                else SelectionContainer { Markdown(doc.getString("readme"), typography = workbenchMarkdownTypography()) }
            }
        } }, confirmButton = { TextButton(close) { Text("关闭") } },
        dismissButton = { TextButton({ revision++ }, enabled = !busy) { Text("重新读取") } })
}
