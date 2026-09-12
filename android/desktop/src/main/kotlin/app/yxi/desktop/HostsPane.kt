package app.yxi.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.launch

/*
 * 主机相关的弹窗：新增 / 编辑表单、首次连接的指纹确认、连接颜色、一键装公钥。
 * 列表本身在 Sidebar.kt（主机是分组头，会话挂在下面）。
 */

/** 新增 / 编辑表单（字段照手机端 HostsScreen 精简）。删除在分组头菜单里，不在这儿。 */
@Composable
fun HostForm(h: Host, isNew: Boolean, onSave: (Host) -> Unit, onClose: () -> Unit) {
    var alias by remember { mutableStateOf(h.alias) }
    var hostname by remember { mutableStateOf(h.hostname) }
    var port by remember { mutableStateOf(h.port.toString()) }
    var username by remember { mutableStateOf(h.username) }
    var usePassword by remember { mutableStateOf(h.keyPath.isBlank()) }   // 新主机探测不到私钥就默认密码
    var keyPath by remember { mutableStateOf(h.keyPath) }
    var password by remember { mutableStateOf(h.password) }
    var err by remember { mutableStateOf("") }

    WorkbenchDialog(
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
                if (usePassword) Text(
                    "不想每次输密码：保存后走主机菜单的「装公钥免密…」，装完自动改用私钥。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                if (err.isEmpty() && p != null) runCatching { onSave(h.copy(
                    alias = alias.trim(), hostname = hostname, port = p, username = username,
                    keyPath = if (usePassword) "" else kp, password = if (usePassword) password else "",
                )) }.onFailure { err = "保存失败，输入已保留：${it.message}" }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClose) { Text("取消") } },
    )
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: String, password: Boolean = false) =
    OutlinedTextField(
        value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
    )

/** 用户自己的密钥：有 id_ed25519 用它，没有再看 id_rsa；都没有留空（表单会默认成密码） */
fun defaultKey(): String =
    listOf("id_ed25519", "id_rsa").map { File(System.getProperty("user.home"), ".ssh/$it") }.firstOrNull { it.isFile }?.path ?: ""

private fun expandHome(p: String) = if (p.startsWith("~")) System.getProperty("user.home") + p.drop(1) else p

/**
 * 一键装公钥（PRD P0-14，手机端 HostsScreen 同款）：用密码连一次，把 Yxi 的公钥写进目标机的
 * authorized_keys，装成就把主机切到桌面版自己的私钥（[DesktopKey]）。指纹核对走侧栏那套弹窗。
 */
@Composable
fun CopyIdDialog(h: Host, keys: FileHostKeys, onSaved: (Host) -> Unit, onClose: () -> Unit) {
    var password by remember { mutableStateOf(h.password) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }
    val t = Tokens.current
    val scope = rememberCoroutineScope()
    WorkbenchDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text("给 ${h.label} 装公钥") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "用密码连一次，把 Yxi 的公钥写进这台机器的 ~/.ssh/authorized_keys，之后免密登录。",
                    style = MaterialTheme.typography.bodySmall, color = t.textSecondary,
                )
                if (!done) Field(password, { password = it }, "密码", password = true)
                msg?.let { Text(it, color = if (done) t.success else t.danger, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = password.isNotEmpty() && !busy && !done, onClick = {
                busy = true; msg = null
                scope.launch {
                    val err = installPublicKey(h, password, keys)
                    busy = false
                    if (err == null) {
                        runCatching { onSaved(h.copy(keyPath = DesktopKey.privFile.path)) }
                            .onSuccess { done = true; msg = "装好了，已切到密钥登录（${DesktopKey.privFile.path}）" }
                            .onFailure { msg = "公钥已装到服务器，但本地主机记录未保存：${it.message}" }
                    } else msg = err
                }
            }) { Text(if (busy) "安装中…" else if (done) "装好了" else "连接并安装") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = { onClose() }) { Text(if (done) "完成" else "取消") } },
    )
}

/** 首次连接的指纹确认（措辞照 Claude Desktop）。答案往 [FileHostKeys.Prompt.answer] 里填，jsch 在 IO 线程上等着。 */
@Composable
fun FingerprintDialog(p: FileHostKeys.Prompt, alias: String) {
    val t = Tokens.current
    // 提示用户去查哪个文件：跟对方给的密钥类型对上，指错文件指纹必然对不上、用户就不敢连
    val keyFile = when { "rsa" in p.keyType -> "rsa"; "ecdsa" in p.keyType -> "ecdsa"; else -> "ed25519" }
    WorkbenchDialog(
        onDismissRequest = { p.answer.complete(false) },
        title = { Text("确认这是 $alias 吗？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("这是 Yxi 第一次连接这台机器，核对指纹后再继续。")
                Text("${p.host}（${p.keyType}）", style = MaterialTheme.typography.bodySmall, color = t.textSecondary)
                Text(p.fingerprint, fontFamily = Mono)
                Text("在服务器上运行 ssh-keygen -lf /etc/ssh/ssh_host_${keyFile}_key.pub 可以看到它的指纹。对不上就别连。", style = MaterialTheme.typography.bodySmall, color = t.textMuted)
            }
        },
        confirmButton = { TextButton({ p.answer.complete(true) }) { Text("指纹一致，连接") } },
        dismissButton = { TextButton({ p.answer.complete(false) }) { Text("取消") } },
    )
}

/** 连接颜色：6 个柔和色里点一个。 */
@Composable
fun ColorDialog(current: Color, onPick: (String) -> Unit, onClose: () -> Unit) {
    val t = Tokens.current
    WorkbenchDialog(
        onDismissRequest = onClose,
        title = { Text("连接颜色") },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HostColors.forEach { c ->
                    Box(
                        Modifier.size(28.dp).clip(CircleShape).background(c)
                            .border(if (c == current) 2.dp else 0.dp, if (c == current) t.textPrimary else Color.Transparent, CircleShape)
                            .clickable { onPick("#%06X".format(c.toArgb() and 0xFFFFFF)) },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClose) { Text("取消") } },
    )
}
