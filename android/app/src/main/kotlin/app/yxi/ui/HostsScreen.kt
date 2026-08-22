package app.yxi.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.yxi.ssh.Host
import app.yxi.ssh.HostStore
import app.yxi.ssh.KeyManager
import app.yxi.ssh.Vault
import java.util.UUID

private val Pill = RoundedCornerShape(100.dp)

/** 主机列表。**不是预置列表** —— 随时能加「以后才有的」服务器（PRD §2.4）。 */
@Composable
fun HostsScreen(
    store: HostStore,
    keys: KeyManager,
    onOpen: (Host) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hosts by store.hosts.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var showKey by remember { mutableStateOf(false) }
    var installTarget by remember { mutableStateOf<Host?>(null) }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(18.dp, 14.dp, 18.dp, 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("主机", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            IconTextButton("公钥") { showKey = true }
            Spacer(Modifier.width(8.dp))
            IconTextButton("＋", primary = true) { adding = true }
        }

        if (hosts.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "还没有主机\n点右上角 ＋ 加一台",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        } else {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(14.dp, 0.dp, 14.dp, 18.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                items(hosts, key = { it.id }) { h ->
                    HostRow(h, onClick = { onOpen(h) }, onLongClick = { installTarget = h })
                }
            }
        }
    }

    if (adding) {
        AddHostSheet(store, keys, onDone = { adding = false })
    }
    if (showKey) {
        PublicKeySheet(keys) { showKey = false }
    }
    installTarget?.let { h ->
        // 连接一律走共用的 connector —— 这里曾经自己 new 了个 SshSession 且 prompt 传 null，
        // 结果「给没连过的新主机装公钥」永远失败（见 TROUBLESHOOTING #24 / #25）
        InstallKeySheet(rememberSshConnector(store, keys, h), keys, store, h) { installTarget = null }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HostRow(h: Host, onClick: () -> Unit, onLongClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(Modifier.padding(16.dp, 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(h.alias, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(3.dp))
                Text(
                    h.display,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Surface(
                color = if (h.useKey) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = Pill,
            ) {
                Text(
                    if (h.useKey) "密钥" else "密码",
                    Modifier.padding(11.dp, 5.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (h.useKey) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/** 加主机：任意 IP、**任意端口**、用户名、密码或密钥 —— 四样都不能写死。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddHostSheet(store: HostStore, keys: KeyManager, onDone: () -> Unit) {
    var alias by remember { mutableStateOf("") }
    var hostname by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }
    var username by remember { mutableStateOf("root") }
    var usePassword by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDone, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            Modifier.padding(18.dp, 0.dp, 18.dp, 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("加新主机", style = MaterialTheme.typography.titleLarge)

            Field(alias, { alias = it }, "名字（随便起，只给你自己看）")
            // ⚠️ 别名≠地址：手机上没有 ~/.ssh/config，「station」「天亮」这类 SSH 别名解析不了，
            // 下面那栏必须是真地址。标签曾经写「主机名 / IP」，等于在邀请用户填别名。
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) { Field(hostname, { hostname = it }, "IP 或域名，如 38.244.50.31", mono = true) }
                // ⚠️ 端口不能写死 22 —— 客户那台 Windows 走 2222
                Box(Modifier.width(96.dp)) {
                    Field(port, { port = it.filter(Char::isDigit).take(5) }, "端口", mono = true, number = true)
                }
            }
            Field(username, { username = it }, "用户名", mono = true)

            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = Pill) {
                Row(Modifier.padding(4.dp)) {
                    SegItem("密钥", !usePassword, Modifier.weight(1f)) { usePassword = false }
                    SegItem("密码", usePassword, Modifier.weight(1f)) { usePassword = true }
                }
            }

            if (usePassword) {
                Field(password, { password = it }, "密码", password = true)
                Hint("密码用设备密钥加密后保存，不落明文。连上后可以一键装公钥，之后免密。")
            } else {
                Hint("用 App 自己的 ed25519 密钥。先去右上角「公钥」把它贴进目标机的 authorized_keys。")
            }

            Button(
                onClick = {
                    val hn = hostname.trim()
                    if (hn.isEmpty()) return@Button
                    store.upsert(
                        Host(
                            id = UUID.randomUUID().toString(),
                            alias = alias.trim().ifEmpty { hn },
                            hostname = hn,
                            port = port.toIntOrNull() ?: 22,
                            username = username.trim().ifEmpty { "root" },
                            useKey = !usePassword,
                            sealedPassword = if (usePassword && password.isNotEmpty()) Vault.seal(password) else null,
                        )
                    )
                    onDone()
                },
                enabled = hostname.isNotBlank(),
                shape = Pill,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("保存") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PublicKeySheet(keys: KeyManager, onDone: () -> Unit) {
    // 换钥匙之后要重刷，所以是 state 不是 remember 常量
    var gen by remember { mutableStateOf(0) }
    val line = remember(gen) {
        runCatching { keys.publicKeyLine() }
            .getOrElse { "生成失败：${it::class.simpleName}: ${it.message ?: "(无消息)"}" }
    }
    val fp = remember(gen) { runCatching { keys.fingerprint() }.getOrDefault("") }
    var copied by remember { mutableStateOf(false) }
    var confirmRegen by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current

    fun copy() {
        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("ssh public key", line))
        copied = true
    }

    ModalBottomSheet(onDismissRequest = onDone, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            // ⚠️ 要能滚：矮屏（模拟器 720x1280）上底部两个按钮会被挤出屏幕，够不着
            Modifier.verticalScroll(rememberScrollState()).padding(18.dp, 0.dp, 18.dp, 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("这台手机的公钥", style = MaterialTheme.typography.titleLarge)
            Hint("点一下整块就复制。贴进目标机的 ~/.ssh/authorized_keys（一行）。撤销就删掉那一行，不用改 App 任何设置。")
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().clickable { copy() },
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(line, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                    Text(
                        if (copied) "✓ 已复制到剪贴板" else "点这里复制",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (copied) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.outline,
                    )
                }
            }
            if (fp.isNotEmpty()) {
                Text(
                    "指纹 $fp",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button({ copy() }, shape = Pill, modifier = Modifier.weight(1f).height(48.dp)) {
                    Text(if (copied) "已复制" else "复制公钥")
                }
                OutlinedButton(
                    { confirmRegen = true }, shape = Pill,
                    modifier = Modifier.weight(1f).height(48.dp),
                ) { Text("换一把") }
            }
        }
    }

    // ⚠️ 换钥匙是**不可逆**的：旧私钥直接丢，所有装过旧公钥的服务器立刻连不上。
    // 所以必须先问一句，且把后果说清楚——不是「确定吗」这种没信息量的提示。
    if (confirmRegen) {
        AlertDialog(
            onDismissRequest = { confirmRegen = false },
            title = { Text("换一把新密钥？") },
            text = {
                Text(
                    "旧私钥会被丢掉，换不回来。\n\n" +
                        "所有已经装过旧公钥的服务器都会立刻连不上，" +
                        "要么重新装一次新公钥，要么手工删掉 authorized_keys 里那行 yxi@android。\n\n" +
                        "只有在怀疑私钥泄露、或想换台手机重来时才需要这么做。",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton({
                    keys.regenerate(); gen++; copied = false; confirmRegen = false
                }) { Text("换") }
            },
            dismissButton = { TextButton({ confirmRegen = false }) { Text("算了") } },
        )
    }
}

// ——— 小件 ———

@Composable
private fun Field(
    value: String,
    onValue: (String) -> Unit,
    label: String,
    mono: Boolean = false,
    number: Boolean = false,
    password: Boolean = false,
) {
    OutlinedTextField(
        value, onValue,
        label = { Text(label) },
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        textStyle = if (mono) MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
        else MaterialTheme.typography.bodyMedium,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (number) KeyboardType.Number else if (password) KeyboardType.Password else KeyboardType.Text
        ),
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SegItem(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer,
        shape = Pill,
        modifier = modifier.height(44.dp).clickable(onClick = onClick),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
}

@Composable
private fun IconTextButton(text: String, primary: Boolean = false, onClick: () -> Unit) {
    Surface(
        color = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer,
        shape = Pill,
        modifier = Modifier.height(44.dp).clickable(onClick = onClick),
    ) {
        Box(Modifier.padding(horizontal = 18.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                color = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** 一键装公钥：用密码连一次，把 App 的公钥追加进 `authorized_keys`，之后免密。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstallKeySheet(
    connect: Connect,
    keys: KeyManager,
    store: HostStore,
    host: Host,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDone, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            Modifier.padding(18.dp, 0.dp, 18.dp, 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("给 ${host.alias} 装公钥", style = MaterialTheme.typography.titleLarge)
            Hint("用密码连一次，把这台手机的公钥追加进 ~/.ssh/authorized_keys，之后就免密了。相当于 ssh-copy-id。")
            Field(password, { password = it }, "密码", password = true)
            result?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
            Button(
                onClick = {
                    busy = true; result = "连接中…"
                    scope.launch {
                        // 用密码连一次，但指纹校验和别处完全一样（第一次会弹指纹确认）
                        val c = connect(app.yxi.ssh.HostConfig.Auth.Password(password))
                        result = if (c == null) "建不了连接" else runCatching {
                            c.session.connect()
                            val n = c.session.installPublicKey(keys.publicKeyLine()).trim()
                            c.session.disconnect()
                            store.upsert(host.copy(useKey = true, sealedPassword = app.yxi.ssh.Vault.seal(password)))
                            "✅ 装好了（authorized_keys 里现有 $n 行 yxi 公钥），已切到密钥认证"
                        }.getOrElse { c.explain(it) }
                        busy = false
                    }
                },
                enabled = password.isNotEmpty() && !busy,
                shape = Pill,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(if (busy) "处理中…" else "连接并安装") }
        }
    }
}
