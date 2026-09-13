package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import app.yxi.agent.Groups
import app.yxi.ssh.Shell
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

@Composable
internal fun GroupEditorDialog(conn: Conn, original: String?, table: Groups.Table, close: () -> Unit, saved: (String) -> Unit) {
    var name by remember { mutableStateOf(original.orEmpty()) }
    var rule by remember { mutableStateOf(table.rules[original].orEmpty()) }
    var members by remember { mutableStateOf(table.groups[original].orEmpty().toSet()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val candidates = (conn.sessions.map { it.name } + members).distinct().sorted()
    WorkbenchDialog(onDismissRequest = { if (!busy) close() }, title = { Text(if (original == null) "新建协作组" else "编辑协作组") }, text = {
        Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(conn.host.label, style = MaterialTheme.typography.labelMedium)
            OutlinedTextField(name, { name = it }, readOnly = original != null, enabled = !busy, singleLine = true, label = { Text("组名") })
            OutlinedTextField(rule, { rule = it }, enabled = !busy, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 6, label = { Text("组规与分工") })
            Text("选择成员；保存不会自动发消息，组规由服务器现有协作机制读取。", style = MaterialTheme.typography.bodySmall)
            candidates.forEach { candidate ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(candidate in members, { checked -> members = if (checked) members + candidate else members - candidate }, enabled = !busy)
                    Column {
                        Text(candidate, style = MaterialTheme.typography.bodyMedium)
                        Text(conn.sessions.firstOrNull { it.name == candidate }?.cwd ?: "当前会话列表未找到", style = MaterialTheme.typography.labelSmall, color = Tokens.current.textMuted)
                    }
                }
            }
            if (error.isNotBlank()) Text(error, color = Tokens.current.danger)
        }
    }, confirmButton = { TextButton({
        busy = true; error = ""
        scope.launch {
            try {
                val group = name.trim()
                require(group.isNotEmpty() && group.none { it < ' ' }) { "请填写有效组名" }
                val request = JSONObject().put("name", group).put("new", original == null)
                    .put("members", JSONArray(members.sorted())).put("rule", rule.trim())
                    .put("previousMembers", JSONArray(table.groups[original].orEmpty())).put("previousRule", table.rules[original].orEmpty())
                val script = """
import json, os, pathlib, sys, tempfile
p = pathlib.Path.home() / '.yxi' / 'groups.json'
r = json.loads(sys.argv[1])
root = json.loads(p.read_text()) if p.exists() else {'version': 1, 'groups': {}, 'rules': {}}
groups = root.setdefault('groups', {})
rules = root.setdefault('rules', {})
n = r['name']
if r['new']:
    if n in groups: raise ValueError('组名已存在，请刷新后编辑')
elif groups.get(n) != r['previousMembers'] or rules.get(n, '') != r['previousRule']:
    raise ValueError('此分组已被其他客户端修改，请刷新后再编辑')
groups[n] = r['members']
if r['rule']: rules[n] = r['rule']
else: rules.pop(n, None)
p.parent.mkdir(parents=True, exist_ok=True)
fd, temp = tempfile.mkstemp(prefix='groups-', suffix='.tmp', dir=p.parent)
try:
    with os.fdopen(fd, 'w') as f:
        json.dump(root, f, ensure_ascii=False, indent=2)
        f.flush(); os.fsync(f.fileno())
    os.replace(temp, p)
finally:
    if os.path.exists(temp): os.unlink(temp)
print('__YXI_GROUP_SAVED__')
""".trimIndent()
                val result = conn.ssh.exec("python3 -c " + Shell.q(script) + " " + Shell.q(request.toString()))
                check(result.lineSequence().any { it == "__YXI_GROUP_SAVED__" }) { "保存未确认，请刷新分组后核对；编辑仍保留" }
                saved(group)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message.orEmpty() }
            finally { busy = false }
        }
    }, enabled = !busy && name.isNotBlank()) { Text(if (busy) "保存中…" else "保存到服务器") } },
        dismissButton = { TextButton(close, enabled = !busy) { Text("取消") } })
}
