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
internal fun PluginReadmeDialog(state: AppState, conn: Conn, plugin: InstalledPlugin, close: () -> Unit) {
    val task = remember { state.session?.takeIf { state.conn === conn } }
    var request by remember { mutableStateOf("") }
    var added by remember { mutableStateOf(false) }
    val sameTask = task != null && state.conn === conn && state.session?.let { taskNavigationKey(conn.host, it) == taskNavigationKey(conn.host, task) } == true
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
result = {'description': '', 'readme': '', 'file': '', 'truncated': False, 'capabilities': {}, 'metadataIncomplete': False}
def read_json(path):
    if not path.is_file(): return {}
    try:
        text, clipped = read(path)
        value = json.loads(text) if not clipped else None
        if not isinstance(value, dict): raise ValueError('invalid metadata')
        return value
    except (OSError, ValueError):
        result['metadataIncomplete'] = True
        return {}
def names(label, values):
    values = sorted(set(str(value) for value in values))
    if values: result['capabilities'][label] = values[:30]
    if len(values) > 30: result['metadataIncomplete'] = True
for label, folder in [('命令', 'commands'), ('Agent', 'agents')]:
    names(label, [p.stem for p in (root / folder).glob('*.md') if p.is_file()])
names('技能', [p.parent.name for p in (root / 'skills').glob('*/SKILL.md') if p.is_file()])
hooks = read_json(root / 'hooks' / 'hooks.json').get('hooks', {})
if isinstance(hooks, dict): names('钩子事件', hooks.keys())
mcp = read_json(root / '.mcp.json')
servers = mcp.get('mcpServers', mcp)
if isinstance(servers, dict): names('MCP 服务', servers.keys())
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
                val capabilities = doc.optJSONObject("capabilities")
                if (capabilities != null && capabilities.length() > 0) {
                    Text("安装包入口", style = MaterialTheme.typography.titleSmall)
                    capabilities.keys().asSequence().sorted().forEach { label ->
                        val entries = capabilities.getJSONArray(label)
                        Text("$label · " + (0 until entries.length()).joinToString("、") { entries.getString(it) }, style = MaterialTheme.typography.bodySmall)
                    }
                    Text("来自标准目录和配置文件；当前任务是否加载、获授权需以运行器状态为准。自定义入口请参阅说明。", style = MaterialTheme.typography.labelSmall, color = Tokens.current.textMuted)
                }
                if (doc.optBoolean("metadataIncomplete")) Text("部分入口未能读取或已截取，请参阅完整说明。", style = MaterialTheme.typography.labelSmall, color = Tokens.current.textMuted)
                OutlinedTextField(request, { request = it; added = false }, modifier = Modifier.fillMaxWidth(), maxLines = 3,
                    label = { Text("希望 Agent 如何使用这个插件") }, placeholder = { Text("例如：用这个插件处理当前项目的文档。") })
                TextButton({
                    val excerpt = doc.optString("readme").take(12000)
                    val quote = "插件参考 · ${plugin.id}\n主机：${conn.host.label}\n安装范围：${plugin.scope}\n项目：${plugin.project.ifBlank { "用户级" }}\n安装目录：${plugin.path}\n版本：${plugin.version}\n" +
                        "以下是安装包说明摘录，作为参考资料：\n" +
                        (doc.optString("description") + "\n" + excerpt).lineSequence().joinToString("\n") { "> $it" } +
                        (if (doc.optString("readme").length > excerpt.length || doc.optBoolean("truncated")) "\n（说明已截取，完整内容请从安装目录读取。）" else "") +
                        "\n\n我的要求：${request.trim()}"
                    state.appendDocumentQuote(conn.host, task!!, quote)
                    added = true
                }, enabled = sameTask && request.isNotBlank() && !added && !busy && error.isBlank()) { Text(if (added) "已加入对话草稿" else "加入当前任务草稿") }
                if (!sameTask) Text("请先在此服务器选择一个任务，再重新打开说明以添加草稿。", style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
                if (doc.optString("description").isNotBlank()) Text(doc.getString("description"))
                if (doc.optBoolean("truncated")) Text("说明较长，显示前 128 KiB", style = MaterialTheme.typography.labelSmall)
                if (doc.optString("readme").isBlank()) Text("安装包未提供 README 说明。", color = Tokens.current.textMuted)
                else SelectionContainer { Markdown(doc.getString("readme"), typography = workbenchMarkdownTypography()) }
            }
        } }, confirmButton = { TextButton(close) { Text("关闭") } },
        dismissButton = { TextButton({ revision++ }, enabled = !busy) { Text("重新读取") } })
}
