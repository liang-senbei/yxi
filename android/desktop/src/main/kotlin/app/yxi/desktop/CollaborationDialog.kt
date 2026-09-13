package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.yxi.agent.Groups
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

@Composable
fun CollaborationDialog(state: AppState, conn: Conn, close: () -> Unit) {
    var table by remember(conn) { mutableStateOf<Groups.Table?>(null) }
    var error by remember(conn) { mutableStateOf("") }
    var busy by remember(conn) { mutableStateOf(true) }
    var revision by remember(conn) { mutableStateOf(0) }
    var selected by remember(conn) { mutableStateOf("") }
    var section by remember(conn) { mutableStateOf("成员") }
    var editing by remember(conn) { mutableStateOf(false) }
    var editingName by remember(conn) { mutableStateOf<String?>(null) }
    var removing by remember(conn) { mutableStateOf(false) }
    var history by remember(conn) { mutableStateOf(false) }
    var setup by remember(conn) { mutableStateOf(false) }
    var creatingMember by remember(conn) { mutableStateOf(false) }
    if (creatingMember && table != null) {
        val context = "你将加入协作组「$selected」。\n队友：${table!!.groups[selected].orEmpty().joinToString()}\n组规：\n${table!!.rules[selected].orEmpty()}\n\n" +
            "使用 yxi-hub who 查询当前身份与队友，yxi-hub say <成员> <内容> 联系同组成员，reply <消息ID> <内容> 回复。先读取当前组规，遵守文件归属；不要修改其他成员负责的文件。这里的名单是创建时快照，以服务器实时分组为准。"
        NewSessionDialog(conn, { creatingMember = false }, collaborationGroup = selected, groupContext = context) { session ->
            state.select(conn, session); creatingMember = false; close()
        }
        return
    }
    if (setup) { HubSetupDialog(conn.host) { setup = false }; return }
    var assignment by remember(conn) { mutableStateOf<Pair<String, app.yxi.agent.Session>?>(null) }
    assignment?.let { (group, target) ->
        MemberAssignmentDialog(state, conn, target, group, { assignment = null }, close)
        return
    }
    if (history) {
        CollaborationHistoryDialog(conn) { history = false }
        return
    }
    if (editing && table != null) {
        GroupEditorDialog(conn, editingName, table!!, { editing = false }, removing = removing) { name -> selected = name; editing = false; revision++ }
        return
    }
    LaunchedEffect(conn, revision) {
        busy = true; error = ""
        try {
            val raw = conn.ssh.exec("if [ -f \"\$HOME/.yxi/groups.json\" ]; then cat \"\$HOME/.yxi/groups.json\"; else printf '%s' '{\"version\":1,\"groups\":{}}'; fi")
            require(JSONObject(raw).optJSONObject("groups") != null) { "服务器分组文件格式无法读取" }
            table = Groups.parse(raw)
            if (selected !in table!!.groups) selected = table!!.groups.keys.sorted().firstOrNull().orEmpty()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message.orEmpty() }
        finally { busy = false }
    }
    WorkbenchDialog(onDismissRequest = close, title = { Text("协作组 · ${conn.host.label}") }, text = {
        Column(Modifier.widthIn(max = 640.dp).heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("服务器上的分组与组规", style = MaterialTheme.typography.labelMedium, color = Tokens.current.textMuted)
            TextButton({ history = true }) { Text("查看协作记录") }
            TextButton({ setup = true }) { Text("安装或升级协作服务") }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (error.isNotBlank()) Text(error, color = Tokens.current.danger)
            table?.let { data ->
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    TextButton({ removing = false; editingName = null; editing = true }, enabled = !busy && error.isBlank()) { Text("新建组") }
                    TextButton({ removing = false; editingName = selected; editing = true }, enabled = !busy && error.isBlank() && selected in data.groups) { Text("编辑成员与组规") }
                    TextButton({ removing = true; editingName = selected; editing = true }, enabled = !busy && error.isBlank() && selected in data.groups) { Text("移除组", color = Tokens.current.danger) }
                }
                if (data.groups.isEmpty()) Text("此服务器尚未配置协作组。", color = Tokens.current.textMuted)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    data.groups.keys.sorted().forEach { name ->
                        FilterChip(selected == name, { selected = name; section = "成员" }, label = { Text("$name · ${data.groups[name].orEmpty().size} 位成员") })
                    }
                }
                if (selected in data.groups) {
                    HorizontalDivider()
                    Text(selected, style = MaterialTheme.typography.titleMedium)
                    WorkbenchTabs(listOf("成员", "指派", "组规", "投递控制"), section, { section = it })
                    if (section == "成员") TextButton({ creatingMember = true }, enabled = !busy && error.isBlank()) { Text("在组内新建 Agent") }
                    if (section == "投递控制") key(conn, selected) { GroupDeliveryControls(conn, selected) }
                    if (section == "组规") SelectionContainer { Text(data.rules[selected].orEmpty().ifBlank { "尚未设置组规，可在上方编辑成员与组规中填写。" }, style = MaterialTheme.typography.bodySmall) }
                    val assignments = state.instructions.entries.filter { it.assignmentGroup == selected && it.assignmentHost == projectKey(conn.host, "/") }
                    if (section == "指派" && assignments.isEmpty()) Text("暂无本机指派记录，可在成员页选择任务进行指派。", style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
                    if (section == "指派" && assignments.isNotEmpty()) {
                        Text("本机指派记录 · ${assignments.size}", style = MaterialTheme.typography.titleSmall)
                        assignments.asReversed().forEach { item ->
                            val target = conn.sessions.firstOrNull { taskNavigationKey(conn.host, it) == item.taskKey }
                            OutlinedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(when (item.status) {
                                        InstructionStatus.Local -> "本地待发送"
                                        InstructionStatus.Delivering -> "投递中"
                                        InstructionStatus.Unknown -> "投递状态待确认"
                                        InstructionStatus.Accepted -> "运行器已接收"
                                        InstructionStatus.Cancelled -> "已撤回"
                                        InstructionStatus.Resolved -> "已人工核对"
                                    }, style = MaterialTheme.typography.labelMedium)
                                    Text(item.text.substringAfter("用户要求：\n", item.text), maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                                    Text("指派 ID：${item.id}", style = MaterialTheme.typography.labelSmall, color = Tokens.current.textMuted)
                                    if (target != null) TextButton({ state.select(conn, target); state.tab = 0; close() }) { Text("查看 ${target.short}") }
                                    else Text("目标任务当前不可见，记录仍保留", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                    if (section == "成员" && data.groups[selected].orEmpty().isEmpty()) Text("此组暂无成员，可以新建 Agent 或加入现有任务。", style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
                    if (section == "成员") data.groups[selected].orEmpty().forEach { name ->
                        val session = conn.sessions.firstOrNull { it.name == name }
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(session?.let { state.navigation.title(taskNavigationKey(conn.host, it)) } ?: name, style = MaterialTheme.typography.titleSmall)
                                Text(if (session == null) "当前会话列表未找到 · 可能离线或已结束" else "${session.state.label} · ${if (session.isCodex) "Codex" else "Claude Code"}", style = MaterialTheme.typography.labelSmall, color = Tokens.current.textMuted)
                                if (session != null) {
                                    Text(session.cwd, style = MaterialTheme.typography.bodySmall)
                                    TextButton({ state.select(conn, session); close() }) { Text("打开成员任务") }
                                    TextButton({ assignment = selected to session }) { Text("指派任务") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(close) { Text("关闭") } }, dismissButton = {
        TextButton({ revision++ }, enabled = !busy) { Text("刷新分组") }
    })
}
