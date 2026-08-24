package app.yxi.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.yxi.BuildConfig
import app.yxi.agent.Update
import app.yxi.ssh.Host
import app.yxi.ssh.HostStore
import app.yxi.ssh.KeyManager
import app.yxi.ssh.Sftp
import app.yxi.ssh.SshSession
import app.yxi.ui.theme.*
import kotlinx.coroutines.launch

private val Pill = RoundedCornerShape(100.dp)
private val Mono = FontFamily.Monospace

/**
 * 设置页。把原来散在各处的入口收在一起：版本、更新、公钥、后台放行。
 *
 * ⚠️ 「检查更新」有**三种**结果，必须分清（决策 D23）：
 * 有新版本 / 已是最新 / **连不上没查到**。
 * 把「没查到」显示成「已是最新」是在骗用户 —— 他会以为自己是最新版，
 * 而实际可能落后好几版、正带着已知的 bug 在用。
 */
@Composable
fun SettingsScreen(
    store: HostStore,
    keys: KeyManager,
    host: Host?,
    /** 共用的连接（[app.yxi.ui.rememberHostSession]）。检查更新直接搭它，不再自己建一条 */
    ssh: SshSession?,
    /** 界面此刻显示的连接错误 —— 诊断报告要带上它，见 [DevMode.diagnose] */
    connectError: String? = null,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var showKey by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Update.Result?>(null) }
    var sftp by remember { mutableStateOf<Sftp?>(null) }
    // 开发者模式：连点三下版本号 → 输口令。藏起来是因为诊断文本里有主机地址、
    // 用户名这些不该随手给旁人看的东西，不是因为它危险
    var taps by remember { mutableStateOf(0) }
    var lastTap by remember { mutableStateOf(0L) }
    var askPass by remember { mutableStateOf(false) }
    var dev by remember { mutableStateOf(DevMode.unlocked(ctx)) }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(t("设置"), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(18.dp, 14.dp, 18.dp, 4.dp))

        // ── 版本 ───────────────────────────────────────────────────
        Card(t("这个 App"), Glyph.Info) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    Modifier.weight(1f).clickable {
                        val now = System.currentTimeMillis()
                        // 超过 1.2 秒就重新数 —— 否则平时零星点几下也会攒够三下
                        taps = if (now - lastTap < 1200) taps + 1 else 1
                        lastTap = now
                        if (taps >= 3) { taps = 0; if (dev) dev = false.also { DevMode.setUnlocked(ctx, false) } else askPass = true }
                    },
                ) {
                    Text(t("版本 %s").format(BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        // versionCode 才是更新比较用的那个数，写出来免得对不上号时抓瞎
                        "versionCode ${BuildConfig.VERSION_CODE}",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = Mono), color = Dim,
                    )
                }
                Button(
                    onClick = {
                        scope.launch {
                            checking = true; result = null
                            val f = sftp ?: runCatching { ssh?.openSftp() }.getOrNull()
                            sftp = f
                            // ⚠️ 报的必须是**真正去连的地址**，不是用户起的名字。
                            // 这里原来打印 alias —— 用户名字栏填的是 IP、地址栏填的是别名「天亮」，
                            // 于是错误信息理直气壮地报了一个它压根没连过的 IP，
                            // 排查因此往端口/防火墙上跑偏了好几轮。见 TROUBLESHOOTING #71。
                            result = if (f == null) Update.Result.Failed(t("连不上 %s，没查成").format(host?.display))
                            else runCatching { Update.checkVerbose(f, BuildConfig.VERSION_CODE) }
                                .getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; Update.Result.Failed(t("查的时候出错：%s").format(it.message)) }
                            checking = false
                        }
                    },
                    enabled = ssh != null && !checking,
                    shape = Pill, modifier = Modifier.height(42.dp),
                ) { Text(if (checking) t("查着…") else t("检查更新")) }
            }
            if (host == null) {
                Hint2(t("还没有主机 —— 更新包放在你自己的服务器上，得先加一台才能查。"))
            }
            when (val r = result) {
                null -> Unit
                is Update.Result.UpToDate ->
                    Line(t("✓ 已是最新（服务器上就是 %s）").format(BuildConfig.VERSION_NAME), Teal)
                is Update.Result.Failed ->
                    // ⚠️ 明确说「没查到」，不能含糊成「已是最新」
                    Line(t("✗ 没查到：%s").format(r.why), MaterialTheme.colorScheme.error)
                is Update.Result.Newer -> {
                    Line(t("有新版本 %s · %s").format(r.update.versionName, r.update.sizeText), Copper)
                    UpdateBanner(sftp, r.update) { result = null }
                }
            }
        }

        // ── 公钥 ───────────────────────────────────────────────────
        Card(t("这台手机的公钥"), Glyph.Key) {
            Hint2(t("贴进目标机的 ~/.ssh/authorized_keys 就能免密连。撤销 = 删掉那一行。"))
            Button({ showKey = true }, shape = Pill, modifier = Modifier.fillMaxWidth().height(44.dp)) {
                Text(t("查看 / 复制 / 换一把"))
            }
        }

        // ── 后台放行 ────────────────────────────────────────────────
        Card(t("手机主动响"), Glyph.Bell) {
            val battery = ignoringBattery(ctx)
            val notif = notificationsOn(ctx)
            // ⚠️ **这一行是后加的，因为原来那两行在骗人。**
            // 用户把通知权限和后台都放行了，看到两个绿勾 + 「缺任何一项都不会响」，
            // 合理地以为搞定了 —— 但真正决定响不响的是**每台主机的铃铛**（`watch`），
            // 它默认是关的，而且**这一页上根本没提过它**。
            // 前台服务只在「有任意一台开了铃铛」时才启动，一台都没开 = 连那条常驻通知都没有，
            // 现象就是「什么都不显示」。见 TROUBLESHOOTING #91。
            val watching = store.hosts.collectAsState().value.count { it.watch }
            StatusRow(t("盯着的机器"), watching > 0, ok = t("%d 台").format(watching)) {
                // 没法直接跳到主机页（这里拿不到导航），说清楚去哪点就行
            }
            if (watching == 0) Hint2(
                t("⚠️ 一台都没开 —— 前面两项放行了也不会响。") +
                    t("去「主机」那一栏，点每台机器右边的铃铛把它打开。")
            )
            // ⚠️ 这一条也得摆出来。它同样能让手机「明明设置好了却不响」——
            // 开着 + 置顶了几个 = 其余会话一律不响。不写在这页上，
            // 下次又是「我明明放行了」的排查（#91）。
            var onlyPinned by remember { mutableStateOf(Pinned.onlyPinned(ctx)) }
            val pinCount = store.hosts.collectAsState().value
                .sumOf { Pinned.get(ctx, it.id).size }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(t("只通知置顶的会话"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(onlyPinned, { onlyPinned = it; Pinned.setOnlyPinned(ctx, it) })
            }
            Hint2(
                when {
                    !onlyPinned -> t("现在是**每个**会话都会响。会话多的时候通知栏会很吵。")
                    pinCount == 0 -> t("⚠️ 一条都没置顶 —— 现在等于全部通知。") +
                        t("去会话列表点卡片右上角的图钉，置顶几个你真正在等的。")
                    else -> t("只有置顶的 %d 个会响，其余的静悄悄干活。").format(pinCount) +
                        t("置顶在会话列表里点卡片右上角的图钉。")
                }
            )
            StatusRow(t("通知权限"), notif) {
                ctx.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            StatusRow(t("后台不受限制"), battery) { askIgnoreBattery(ctx) }
            if (!battery) {
                // ⚠️ 荣耀/华为的「电池优化白名单」只是**其中一道**。真正掐后台的是
                // 「应用启动管理」，那个 Android 没有标准 intent，只能告诉用户路径。
                // 不写出来的话，用户按上面那个开关开完了、以为搞定了，实际还是不响。
                Hint2(t("荣耀/华为还有一道单独的开关：设置 → 应用和服务 → 应用启动管理 → ") +
                    t("找到 Yxi → 关掉「自动管理」，然后三项（自启动/关联启动/后台活动）全打开。"))
            }
            if (!battery || !notif) {
                // ⚠️ 这两项缺一个，「主动响」就是**静默失效** —— 不会报错，只是不响了
                Hint2(
                    t("缺任何一项，Claude 需要你时手机都不会响，而且不会有任何提示。") +
                        t("荣耀 / 华为 的后台管控尤其狠。")
                )
            }
        }

        Card(t("界面风格"), Glyph.Palette) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Skin.Style.entries.forEach { st ->
                    val on = Skin.style == st
                    Surface(
                        color = if (on) CopperContainer else SurfaceContainerHigh,
                        shape = Pill,
                        modifier = Modifier.clickable { Skin.set(ctx, st) },
                    ) {
                        Text(
                            st.label,
                            Modifier.padding(16.dp, 9.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (on) MaterialTheme.colorScheme.onPrimaryContainer else Muted,
                        )
                    }
                }
            }
            Hint2(t("⚠️ 终端永远是深底 —— 彩色输出在浅底上读不了。所以浅色风格下切到终端会亮暗跳一下。"))
        }

        // ⚠️ 这一栏**不翻译**：正在看不懂当前语言的人，得能认出另一个选项。
        // 「简体中文 / English」两个名字都用它们自己的语言写，谁都找得到自己那个。
        Card(t("语言"), Glyph.Globe) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                I18n.Lang.entries.forEach { l ->
                    val on = I18n.lang == l
                    Surface(
                        color = if (on) CopperContainer else SurfaceContainerHigh,
                        shape = Pill,
                        modifier = Modifier.clickable { I18n.set(ctx, l) },
                    ) {
                        Text(
                            l.label,
                            Modifier.padding(16.dp, 9.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (on) MaterialTheme.colorScheme.onTertiaryContainer else Muted,
                        )
                    }
                }
            }
        }

        if (dev) DevCard(ctx, host, store, keys, connectError)

        Card(t("关于"), Glyph.Info) {
            Hint2(
                t("Yxi —— 手机上的 Claude Code 指挥台。\n") +
                    t("全部走 SSH：不开新端口、不要证书、不经过任何第三方服务器。\n") +
                    t("服务器上唯一需要装的是 yxi-hook（就为了让手机能主动响）。")
            )
        }
    }

    if (showKey) PublicKeySheetPublic(keys) { showKey = false }

    if (askPass) {
        var input by remember { mutableStateOf("") }
        var wrong by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { askPass = false },
            title = { Text(t("开发者模式")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        input, { input = it; wrong = false },
                        singleLine = true,
                        label = { Text(t("口令")) },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    )
                    if (wrong) Line(t("口令不对"), MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton({
                    if (DevMode.check(input)) {
                        DevMode.setUnlocked(ctx, true); dev = true; askPass = false
                    } else wrong = true
                }) { Text(t("进")) }
            },
            dismissButton = { TextButton({ askPass = false }) { Text(t("算了")) } },
        )
    }
}

/**
 * 开发者卡片 —— **它存在的意义是让用户能把「手机那头到底怎么了」一键给我。**
 * 我在服务器上看不到手机的任何东西：包没飞到就等于什么都没发生。
 */
@Composable
private fun DevCard(ctx: Context, host: Host?, store: HostStore, keys: KeyManager, uiError: String?) {
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf("") }
    var copied by remember { mutableStateOf(false) }

    Card(t("开发者"), Glyph.Wrench) {
        Hint2(
            t("诊断会把整条连接路径一步步走一遍：解析地址 → 连 TCP → SSH 招呼 → 认证，") +
                t("再挨个试同一个 IP 上的几个端口。里面不含密码和私钥，可以直接贴出来。\n") +
                t("测的是 ") + (host?.let { "${it.username}@${it.hostname}:${it.port}" } ?: t("（还没有主机）")) +
                t(" —— 要换一台就去「会话」页顶部切换。")
        )
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Button(
                onClick = {
                    scope.launch {
                        running = true; copied = false
                        report = DevMode.diagnose(ctx, host, store, keys, uiError)
                        running = false
                    }
                },
                enabled = !running, shape = Pill, modifier = Modifier.weight(1f).height(44.dp),
            ) { Text(if (running) t("测着…（约 30 秒）") else t("跑一次诊断")) }

            if (report.isNotEmpty()) {
                Button(
                    onClick = { DevMode.copy(ctx, report); copied = true },
                    shape = Pill, modifier = Modifier.height(44.dp),
                ) { Text(if (copied) t("已复制") else t("复制")) }
            }
        }
        if (report.isNotEmpty()) {
            Surface(color = SurfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
                // 诊断文本很长，给它自己的滚动区，别把整页撑成一条
                Box(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()).padding(10.dp)) {
                    Text(
                        report,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = Mono),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        Hint2(t("再连点三下版本号 = 退出开发者模式。"))
    }
}

/**
 * 设置里的一节。**照 Gemini 的样子做**：左边一个线性引导图标，右边一段内容，
 * 标题更粗、留白更松。
 *
 * ⚠️ 卡片底色用得很淡（`SurfaceContainerLow`）—— Gemini 靠留白和图标分节，
 * 不靠重描边。深色主题下它就是比页面底稍亮一点的一块，浅色主题下是白卡。
 */
@Composable
private fun Card(title: String, icon: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = SurfaceContainerLow, shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
    ) {
        Row(Modifier.padding(18.dp, 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (icon != null) {
                GlyphIcon(icon, Muted, 22.dp)
            } else {
                Spacer(Modifier.width(22.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = OnSurface)
                content()
            }
        }
    }
}

@Composable
private fun Line(text: String, color: androidx.compose.ui.graphics.Color) =
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color)

@Composable
private fun Hint2(text: String) =
    Text(text, style = MaterialTheme.typography.labelSmall, color = Dim)

@Composable
private fun StatusRow(label: String, on: Boolean, ok: String = t("已放行"), onFix: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = !on, onClick = onFix),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Surface(color = if (on) SurfaceContainerHigh else CopperContainer, shape = Pill) {
            Text(
                if (on) ok else t("去开启"),
                Modifier.padding(12.dp, 5.dp),
                style = MaterialTheme.typography.labelSmall,
                color = if (on) Teal else OnCopperContainer,
            )
        }
    }
}

/**
 * 请求「后台不受限制」。
 *
 * ⚠️ **不能用 `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`** —— 那个打开的是
 * 「**已经**被放行的应用」列表，而我们恰恰还没被放行，所以用户点进去
 * **根本找不到这个 App**，看起来像是跳错了地方。（用户原话：「没有看见我们的 app」）
 *
 * 要的是带 `package:` 的 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`：
 * 它直接对本应用弹一个授权框。厂商 ROM 上可能被拿掉，所以留两级退路：
 * 本应用的设置页 → 全局电池优化列表。
 */
private fun askIgnoreBattery(ctx: Context) {
    val tries = listOf(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(android.net.Uri.parse("package:" + ctx.packageName)),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(android.net.Uri.parse("package:" + ctx.packageName)),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
    )
    for (i in tries) {
        if (runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
    }
}

private fun ignoringBattery(ctx: Context): Boolean = runCatching {
    (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .isIgnoringBatteryOptimizations(ctx.packageName)
}.getOrDefault(true)

private fun notificationsOn(ctx: Context): Boolean = runCatching {
    if (Build.VERSION.SDK_INT < 33) true
    else ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
}.getOrDefault(true)
