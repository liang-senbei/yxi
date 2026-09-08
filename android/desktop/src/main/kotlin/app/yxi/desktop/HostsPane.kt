package app.yxi.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/** 左栏顶部：主机列表 + 新增 / 编辑表单 + 首次连接的指纹确认框。点一台就连（一次只连一台，先关上一台）。 */
@Composable
fun HostsPane(state: AppState) {
    var hosts by remember { mutableStateOf(Store.hosts()) }
    var editing by remember { mutableStateOf<Host?>(null) }
    var note by remember { mutableStateOf("") }       // 不属于某条连接的错（Conn 都没建出来）
    val keys = remember { FileHostKeys() }
    val scope = rememberCoroutineScope()

    fun save(list: List<Host>) { hosts = list; Store.save(list) }
    fun drop() { state.conn?.close(); state.conn = null; state.session = null }
    fun connect(h: Host) {
        drop(); note = ""
        // 私钥文件没了会在 Conn 构造时就炸（toConfig 读文件），不算连接错误
        val c = runCatching { Conn(h, keys) }.getOrElse { note = "连不了：${it.message}"; return }
        state.conn = c
        scope.launch {
            c.connect()
            if (c.status == Conn.Status.Failed) c.error = explain(keys.changedDetected, c.error)
        }
    }

    Column(Modifier.fillMaxWidth().padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("主机", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton({ editing = Host(id = UUID.randomUUID().toString(), alias = "", hostname = "", keyPath = defaultKey()) }) { Icon(Icons.Default.Add, "加主机") }
        }
        if (note.isNotBlank()) Text(note, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        if (hosts.isEmpty()) Text("还没有主机，点 + 加一台", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
            hosts.forEach { h ->
                val conn = state.conn?.takeIf { it.host.id == h.id }
                HostRow(h, conn?.status, onClick = { connect(h) }, onEdit = { editing = h })
                if (conn?.status == Conn.Status.Failed) {
                    Text(conn.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 18.dp))
                    if (keys.changedDetected) TextButton({ keys.forget(h); conn.error = ""; conn.status = Conn.Status.Idle }) { Text("确认过了，删除旧指纹") }
                }
            }
        }
    }

    editing?.let { h ->
        HostForm(
            h, isNew = hosts.none { it.id == h.id },
            onSave = { n ->
                save(if (hosts.any { it.id == n.id }) hosts.map { if (it.id == n.id) n else it } else hosts + n)
                if (state.conn?.host?.id == n.id) drop()   // 改了地址 / 认证，旧连接作废，再点一次重连
                editing = null
            },
            onDelete = { if (state.conn?.host?.id == h.id) drop(); keys.forget(h); save(hosts.filter { it.id != h.id }); editing = null },
            onClose = { editing = null },
        )
    }

    keys.pending?.let { p ->
        AlertDialog(
            onDismissRequest = { p.answer.complete(false) },
            title = { Text("第一次连这台主机") },
            text = { Text("${p.host}（${p.keyType}）\n\n指纹\n${p.fingerprint}\n\n请核对它跟服务器上 ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub 的输出一致。不一致就别连。") },
            confirmButton = { TextButton({ p.answer.complete(true) }) { Text("指纹对得上，连") } },
            dismissButton = { TextButton({ p.answer.complete(false) }) { Text("取消") } },
        )
    }
}

/** 一行主机：状态点（灰未连 / 黄连接中 / 绿已连 / 红失败）+ 别名 + user@host:port + 编辑。 */
@Composable
private fun HostRow(h: Host, status: Conn.Status?, onClick: () -> Unit, onEdit: () -> Unit) {
    val dot = when (status) {
        Conn.Status.Connected -> Color(0xFF2E7D32)
        Conn.Status.Connecting -> Color(0xFFF9A825)
        Conn.Status.Failed -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .background(if (status != null) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent, MaterialTheme.shapes.small)
            .padding(start = 6.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(dot, CircleShape))
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(h.alias.ifBlank { h.hostname }, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${h.username}@${h.hostname}:${h.port}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onEdit, Modifier.size(28.dp)) { Icon(Icons.Default.Edit, "编辑", Modifier.size(16.dp)) }
    }
}

/** 新增 / 编辑表单（字段照手机端 HostsScreen 精简）。 */
@Composable
private fun HostForm(h: Host, isNew: Boolean, onSave: (Host) -> Unit, onDelete: () -> Unit, onClose: () -> Unit) {
    var alias by remember { mutableStateOf(h.alias) }
    var hostname by remember { mutableStateOf(h.hostname) }
    var port by remember { mutableStateOf(h.port.toString()) }
    var username by remember { mutableStateOf(h.username) }
    var usePassword by remember { mutableStateOf(h.keyPath.isBlank()) }   // 新主机探测不到私钥就默认密码
    var keyPath by remember { mutableStateOf(h.keyPath) }
    var password by remember { mutableStateOf(h.password) }
    var err by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (isNew) "加新主机" else "改主机") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(alias, { alias = it }, "名字（随便起，只给你自己看）")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { Field(hostname, { hostname = it.trim() }, "IP 或域名，如 38.244.50.31") }
                    Box(Modifier.width(88.dp)) { Field(port, { port = it.filter(Char::isDigit).take(5) }, "端口") }
                }
                Field(username, { username = it.trim() }, "用户名")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(!usePassword, { usePassword = false }); Text("私钥文件")
                    Spacer(Modifier.width(12.dp))
                    RadioButton(usePassword, { usePassword = true }); Text("密码")
                }
                if (usePassword) Field(password, { password = it }, "密码", password = true)
                else Field(keyPath, { keyPath = it }, "私钥文件路径")
                if (err.isNotBlank()) Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton({
                val p = port.toIntOrNull()
                val kp = expandHome(keyPath.trim())
                err = when {
                    hostname.isBlank() -> "地址不能为空"
                    p == null || p !in 1..65535 -> "端口要在 1–65535 之间"
                    username.isBlank() -> "用户名不能为空"
                    !usePassword && !File(kp).isFile -> "私钥文件不存在：$kp"
                    usePassword && password.isEmpty() -> "密码不能为空"
                    else -> ""
                }
                if (err.isEmpty() && p != null) onSave(h.copy(
                    alias = alias.trim(), hostname = hostname, port = p, username = username,
                    keyPath = if (usePassword) "" else kp, password = if (usePassword) password else "",
                ))
            }) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (!isNew) TextButton({ if (confirmDelete) onDelete() else confirmDelete = true }) {
                    Text(if (confirmDelete) "真的删？再点一次" else "删除", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClose) { Text("取消") }
            }
        },
    )
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: String, password: Boolean = false) =
    OutlinedTextField(
        value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
    )

/** 用户自己的密钥：有 id_ed25519 用它，没有再看 id_rsa；都没有留空（表单会默认成密码） */
private fun defaultKey(): String =
    listOf("id_ed25519", "id_rsa").map { File(System.getProperty("user.home"), ".ssh/$it") }.firstOrNull { it.isFile }?.path ?: ""

private fun expandHome(p: String) = if (p.startsWith("~")) System.getProperty("user.home") + p.drop(1) else p

/** 把 jsch 的报错翻成人话（照手机端 SshConnect.explain 精简） */
private fun explain(changed: Boolean, m: String) = when {
    changed -> "主机指纹变了，可能是中间人，已拒绝连接。确认服务器确实重装过，再删除旧记录。"
    "reject HostKey" in m -> "你取消了指纹确认，所以没连。"
    "Auth fail" in m || "Auth cancel" in m -> "认证被拒：密码不对，或服务器的 authorized_keys 里没有这把公钥。"
    else -> "连不上：$m"
}
