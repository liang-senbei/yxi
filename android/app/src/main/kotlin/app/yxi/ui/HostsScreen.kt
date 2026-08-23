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
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var batteryHint by remember { mutableStateOf(false) }
    val hosts by store.hosts.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var showKey by remember { mutableStateOf(false) }
    var installTarget by remember { mutableStateOf<Host?>(null) }
    var editing by remember { mutableStateOf<Host?>(null) }

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
                    HostRow(
                        h,
                        ctx = ctx,
                        onClick = { onOpen(h) },
                        onLongClick = { editing = h },
                        onWatch = {
                            val on = !h.watch
                            store.upsert(h.copy(watch = on))
                            app.yxi.watch.EventService.sync(ctx, store.hosts.value.any { it.watch })
                            // 第一次打开铃铛时提醒放行后台 —— 见 BatteryHint 的注释
                            if (on && !ignoringBattery(ctx)) batteryHint = true
                        },
                    )
                }
            }
        }
    }

    if (adding) {
        AddHostSheet(store, keys, ctx, onDone = { adding = false })
    }
    editing?.let { h ->
        AddHostSheet(
            store, keys, ctx, editing = h,
            onInstallKey = { editing = null; installTarget = h },
            onDone = { editing = null },
        )
    }
    if (showKey) {
        PublicKeySheetPublic(keys) { showKey = false }
    }
    if (batteryHint) BatteryHint(ctx) { batteryHint = false }
    installTarget?.let { h ->
        // 连接一律走共用的 connector —— 这里曾经自己 new 了个 SshSession 且 prompt 传 null，
        // 结果「给没连过的新主机装公钥」永远失败（见 TROUBLESHOOTING #24 / #25）
        InstallKeySheet(rememberSshConnector(store, keys, h), keys, store, h) { installTarget = null }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HostRow(
    h: Host,
    ctx: android.content.Context,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onWatch: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Column(Modifier.padding(16.dp, 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(h.alias, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(3.dp))
                // ⚠️ 地址栏里混进中文/全角字符是**最贵的一种错**：连不上，
                // 而错误信息在别处，用户看着列表觉得一切正常。所以在列表里就标出来 ——
                // 这台主机的地址是「天亮」（一个 SSH 别名），在列表里躺了好几天没人发现。
                val bad = app.yxi.ssh.HostInput.suspiciousChar(h.hostname)
                Text(
                    h.display,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = if (bad != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.outline,
                )
                if (bad != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "⚠️ 地址里有 $bad —— 连不上。长按改。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            // 铃铛：让手机为这台机器主动响。⚠️ 需要那台机器上装了 server/install.sh
            Surface(
                color = if (h.watch) MaterialTheme.colorScheme.tertiaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = Pill,
                modifier = Modifier.padding(end = 8.dp).clickable(onClick = onWatch),
            ) {
                // ⚠️ 开/关不能只靠换图形，**颜色也要变** —— 铃铛和静音铃铛在 18dp
                // 下轮廓很像，光看形状容易看错。而这两个状态后果差很远：
                // 关着 = Claude 需要你时手机不会响，且没有任何提示
                Box(Modifier.padding(9.dp, 5.dp)) {
                    GlyphIcon(
                        if (h.watch) Glyph.Bell else Glyph.BellOff,
                        if (h.watch) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.outline,
                        18.dp,
                    )
                }
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
        // 用量细线。⚠️ 没缓存过就什么都不画 —— 不为了这条线去连每一台机器
        UsageStrip(ctx, h.id)
        }
    }
}

/**
 * 加 / 改主机：任意 IP、**任意端口**、用户名、密码或密钥 —— 四样都不能写死。
 *
 * ⚠️ **必须能改、能删。** 早先只有「加」：点一下是连接、长按是装公钥，
 * 于是地址打错、或者要换个端口，用户**一点办法都没有**（连删都删不掉，
 * 只能卸载重装）。而「地址里混进全角字符」正是最常见的一种错 ——
 * 我们既提示了「删掉重加」，就得真有地方能删。见 TROUBLESHOOTING #68。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddHostSheet(
    store: HostStore,
    keys: KeyManager,
    ctx: android.content.Context,
    editing: Host? = null,
    onInstallKey: () -> Unit = {},
    onDone: () -> Unit,
) {
    var alias by remember { mutableStateOf(editing?.alias ?: "") }
    var hostname by remember { mutableStateOf(editing?.hostname ?: "") }
    var port by remember { mutableStateOf((editing?.port ?: 22).toString()) }
    var username by remember { mutableStateOf(editing?.username ?: "root") }
    var usePassword by remember { mutableStateOf(editing?.useKey == false) }
    var password by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDone, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            // ⚠️ **表要能滚。** 半开的表放不下这么多字段，「保存」落在屏幕外 ——
            // 用户得先把表往上拖才够得着，而没有任何东西提示他要拖。
            // imePadding：软键盘弹起来时同样会盖住「保存」。见 TROUBLESHOOTING #68。
            Modifier.verticalScroll(rememberScrollState()).imePadding()
                .padding(18.dp, 0.dp, 18.dp, 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (editing != null) "改主机" else "加新主机", style = MaterialTheme.typography.titleLarge)

            Field(alias, { alias = it }, "名字（随便起，只给你自己看）")
            // ⚠️ 别名≠地址：手机上没有 ~/.ssh/config，「station」「天亮」这类 SSH 别名解析不了，
            // 下面那栏必须是真地址。标签曾经写「主机名 / IP」，等于在邀请用户填别名。
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) {
                    Field(
                        hostname,
                        {
                            // ⚠️ **打字的时候只做「全角→半角」这种安全归一，绝不动结构。**
                            // 试过「一次进来一大段就当粘贴、顺手拆出 :port」——
                            // 不成立：输入法整词上屏、adb 成块送，都会触发，
                            // 结果是打到 `…147:22` 时端口被切走、剩下的 `22` 落回地址栏。
                            // **在用户手指底下改他正在打的东西，跟「列表在手指下重排」是同一类错误。**
                            // 拆分放到保存时做 —— 那时整串才是完整的。
                            hostname = app.yxi.ssh.HostInput.normalize(it)
                        },
                        "IP 或域名，如 38.244.50.31", mono = true,
                    )
                }
                // ⚠️ 端口不能写死 22 —— 客户那台 Windows 走 2222
                Box(Modifier.width(96.dp)) {
                    Field(port, { port = it.filter(Char::isDigit).take(5) }, "端口", mono = true, number = true)
                }
            }
            run {
                val p = app.yxi.ssh.HostInput.parse(hostname)
                if (p.user != null || p.port != null) {
                    Text(
                        "保存时会拆成：地址 ${p.host}" +
                            (p.user?.let { " · 用户名 $it" } ?: "") +
                            (p.port?.let { " · 端口 $it" } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            app.yxi.ssh.HostInput.suspiciousChar(hostname)?.let {
                Text(
                    "地址里有个连不上的字符：$it —— 多半是中文输入法打出来的，删掉重打",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Field(username, { username = it }, "用户名", mono = true)

            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = Pill) {
                Row(Modifier.padding(4.dp)) {
                    SegItem("密钥", !usePassword, Modifier.weight(1f)) { usePassword = false }
                    SegItem("密码", usePassword, Modifier.weight(1f)) { usePassword = true }
                }
            }

            if (usePassword) {
                Field(password, { password = it }, if (editing?.sealedPassword != null) "密码（留空 = 不改）" else "密码", password = true)
                Hint("密码用设备密钥加密后保存，不落明文。连上后可以一键装公钥，之后免密。")
            } else {
                Hint("用 App 自己的 ed25519 密钥。先去右上角「公钥」把它贴进目标机的 authorized_keys。")
            }

            Button(
                onClick = {
                    // 保存时再拆一次 —— 手打的 `ip:2222` / `root@ip` 到这一刻才是完整的
                    val parsed = app.yxi.ssh.HostInput.parse(hostname)
                    val hn = parsed.host
                    if (hn.isEmpty()) return@Button
                    // 地址栏里带的 `:port` / `root@` 优先于另外两栏里的值 ——
                    // 用户刚敲进去的那一串才是他最新的意思
                    val pt = parsed.port ?: port.toIntOrNull() ?: 22
                    store.upsert(
                        Host(
                            id = editing?.id ?: UUID.randomUUID().toString(),
                            alias = alias.trim().ifEmpty { hn },
                            hostname = hn,
                            port = pt,
                            username = (parsed.user ?: username).trim().ifEmpty { "root" },
                            useKey = !usePassword,
                            sealedPassword = when {
                                !usePassword -> null
                                password.isNotEmpty() -> Vault.seal(password)
                                // 编辑时密码栏留空 = 不动原来那份密文
                                else -> editing?.sealedPassword
                            },
                            // ⚠️ **改了地址或端口，存的主机指纹就必须作废。**
                            // 那把指纹属于旧机器；留着的话下次连新机器会报「指纹变了」——
                            // 那是中间人警告的措辞，会把一次正常的改配置说成攻击。
                            hostKey = editing?.hostKey?.takeIf {
                                editing.hostname == hn && editing.port == pt
                            },
                            watch = editing?.watch ?: false,
                        )
                    )
                    onDone()
                },
                enabled = hostname.isNotBlank(),
                shape = Pill,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("保存") }

            if (editing != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) { IconTextButton("装公钥", onClick = onInstallKey) }
                    Box(Modifier.weight(1f)) {
                        // 删除要两下 —— 手机上误触一下就没了，而主机记录里有密码密文
                        IconTextButton(if (confirmDelete) "再点一次删除" else "删除") {
                            if (!confirmDelete) { confirmDelete = true; return@IconTextButton }
                            store.remove(editing.id)
                            // 删的可能正是唯一开着铃铛的那台 —— 不同步，前台服务会继续盯一台不存在的机器
                            app.yxi.watch.EventService.sync(ctx, store.hosts.value.any { it.watch })
                            onDone()
                        }
                    }
                }
                Hint("长按主机就能回到这里。改地址或端口会作废已记住的指纹，下次连接重新确认一次。")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PublicKeySheetPublic(keys: KeyManager, onDone: () -> Unit) {
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
                        }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; c.explain(it) }
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

/** 系统有没有把这个 app 从省电优化里放出来。 */
private fun ignoringBattery(ctx: android.content.Context): Boolean = runCatching {
    val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
    pm.isIgnoringBatteryOptimizations(ctx.packageName)
}.getOrDefault(true)   // 查不到就别烦用户

/**
 * ⚠️ **这是「手机主动响」唯一会静默失效的地方，所以必须当面说清楚。**
 *
 * 前台服务能扛住系统的低内存回收（`START_STICKY`，实测系统杀掉 15 秒后自己回来），
 * 但**扛不住 force-stop** —— 而荣耀 MagicOS / 华为 EMUI 的后台管控就是 force-stop。
 * 被那样杀掉之后 Android 不会再拉起它，通知就**无声无息地停了**：
 * 用户不会收到任何错误，只会觉得「怎么最近不响了」。
 *
 * 所以放行后台不是「优化建议」，是这个功能能不能用的前提。
 */
@Composable
private fun BatteryHint(ctx: android.content.Context, onDone: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("还要放行后台运行") },
        text = {
            Text(
                "这台机器上的 Claude 需要你时，手机靠一条常驻连接来响。\n\n" +
                    "系统的省电优化会把它掐掉 —— 荣耀 / 华为 尤其狠，" +
                    "而且掐掉之后不会有任何提示，你只会觉得「怎么不响了」。\n\n" +
                    "去设置里把 Yxi 设成「允许后台活动 / 不受限制」。",
                style = MaterialTheme.typography.bodySmall,
            )
        },
        confirmButton = {
            TextButton({
                runCatching {
                    ctx.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                onDone()
            }) { Text("去设置") }
        },
        dismissButton = { TextButton(onDone) { Text("知道了") } },
    )
}
