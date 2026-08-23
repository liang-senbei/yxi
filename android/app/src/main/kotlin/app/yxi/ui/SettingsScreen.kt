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
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var showKey by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Update.Result?>(null) }
    var sftp by remember { mutableStateOf<Sftp?>(null) }
    val connect = host?.let { rememberSshConnector(store, keys, it) }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("设置", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(18.dp, 14.dp, 18.dp, 4.dp))

        // ── 版本 ───────────────────────────────────────────────────
        Card("这个 App") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("版本 ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        // versionCode 才是更新比较用的那个数，写出来免得对不上号时抓瞎
                        "versionCode ${BuildConfig.VERSION_CODE}",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = Mono), color = Dim,
                    )
                }
                Button(
                    onClick = {
                        val c = connect ?: return@Button
                        scope.launch {
                            checking = true; result = null
                            val f = sftp ?: runCatching {
                                val s = c().also { it?.session?.connect() }?.session
                                s?.openSftp()
                            }.getOrNull()
                            sftp = f
                            result = if (f == null) Update.Result.Failed("连不上 ${host?.alias}，没查成")
                            else runCatching { Update.checkVerbose(f, BuildConfig.VERSION_CODE) }
                                .getOrElse { Update.Result.Failed("查的时候出错：${it.message}") }
                            checking = false
                        }
                    },
                    enabled = host != null && !checking,
                    shape = Pill, modifier = Modifier.height(42.dp),
                ) { Text(if (checking) "查着…" else "检查更新") }
            }
            if (host == null) {
                Hint2("还没有主机 —— 更新包放在你自己的服务器上，得先加一台才能查。")
            }
            when (val r = result) {
                null -> Unit
                is Update.Result.UpToDate ->
                    Line("✓ 已是最新（服务器上就是 ${BuildConfig.VERSION_NAME}）", Teal)
                is Update.Result.Failed ->
                    // ⚠️ 明确说「没查到」，不能含糊成「已是最新」
                    Line("✗ 没查到：${r.why}", MaterialTheme.colorScheme.error)
                is Update.Result.Newer -> {
                    Line("有新版本 ${r.update.versionName} · ${r.update.sizeText}", Copper)
                    UpdateBanner(sftp, r.update) { result = null }
                }
            }
        }

        // ── 公钥 ───────────────────────────────────────────────────
        Card("这台手机的公钥") {
            Hint2("贴进目标机的 ~/.ssh/authorized_keys 就能免密连。撤销 = 删掉那一行。")
            Button({ showKey = true }, shape = Pill, modifier = Modifier.fillMaxWidth().height(44.dp)) {
                Text("查看 / 复制 / 换一把")
            }
        }

        // ── 后台放行 ────────────────────────────────────────────────
        Card("手机主动响") {
            val battery = ignoringBattery(ctx)
            val notif = notificationsOn(ctx)
            StatusRow("通知权限", notif) {
                ctx.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            StatusRow("后台不受限制", battery) {
                runCatching {
                    ctx.startActivity(
                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
            if (!battery || !notif) {
                // ⚠️ 这两项缺一个，「主动响」就是**静默失效** —— 不会报错，只是不响了
                Hint2(
                    "缺任何一项，Claude 需要你时手机都不会响，而且不会有任何提示。" +
                        "荣耀 / 华为 的后台管控尤其狠。"
                )
            }
        }

        Card("关于") {
            Hint2(
                "Yxi —— 手机上的 Claude Code 指挥台。\n" +
                    "全部走 SSH：不开新端口、不要证书、不经过任何第三方服务器。\n" +
                    "服务器上唯一需要装的是 yxi-hook（就为了让手机能主动响）。"
            )
        }
    }

    if (showKey) PublicKeySheetPublic(keys) { showKey = false }
}

@Composable
private fun Card(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = SurfaceContainerLow, shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
    ) {
        Column(Modifier.padding(16.dp, 14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = Muted)
            content()
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
private fun StatusRow(label: String, ok: Boolean, onFix: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = !ok, onClick = onFix),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Surface(color = if (ok) SurfaceContainerHigh else CopperContainer, shape = Pill) {
            Text(
                if (ok) "已放行" else "去开启",
                Modifier.padding(12.dp, 5.dp),
                style = MaterialTheme.typography.labelSmall,
                color = if (ok) Teal else OnCopperContainer,
            )
        }
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
