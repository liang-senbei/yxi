package app.yxi

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.yxi.agent.Attachments
import app.yxi.agent.Session
import app.yxi.agent.SessionProbe
import app.yxi.ssh.Host
import app.yxi.ssh.HostStore
import app.yxi.ssh.KeyManager
import app.yxi.ssh.KnownHosts
import app.yxi.ssh.SshSession
import app.yxi.ui.I18n
import app.yxi.ui.ago
import app.yxi.ui.t
import app.yxi.ui.theme.YxiTheme
import app.yxi.watch.EventService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **分享到某个会话** —— 从任意 app 把报错/截图/文件甩进某个 Claude 会话。
 * 两步：选会话 → 送进去。优先借盯梢服务已建好的连接（[EventService.liveConn]），没有就现连第一台主机。
 */
class ShareActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        I18n.load(this)
        app.yxi.agent.Tr.fn = { zh -> app.yxi.ui.t(zh) }   // 分享可从全新进程冷启动进来（SEND 入口），不接 Tr 的话 core 里 Attachments/Uploader 的文案不翻
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)
        // ⚠️ 相册里一次分享多张走的是 SEND_MULTIPLE，EXTRA_STREAM 是个列表 —— 原来只认单个，多选进不来
        val uris: List<Uri> = if (intent?.action == Intent.ACTION_SEND_MULTIPLE) {
            (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)).orEmpty()
        } else listOfNotNull(
            if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent?.getParcelableExtra(Intent.EXTRA_STREAM)
        )
        val store = HostStore(applicationContext)
        val keys = KeyManager(applicationContext)
        setContent { YxiTheme { SharePicker(store, keys, text, uris) { finish() } } }
    }
}

@Composable
private fun SharePicker(store: HostStore, keys: KeyManager, text: String?, uris: List<Uri>, onDone: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf<Host?>(null) }
    var ssh by remember { mutableStateOf<SshSession?>(null) }
    var owned by remember { mutableStateOf(false) }   // 自己新建的连接，用完要关；借来的不能关
    var sessions by remember { mutableStateOf<List<Session>?>(null) }
    var status by remember { mutableStateOf(t("连接中…")) }
    var sending by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val live = EventService.liveConn()
        if (live != null) { host = live.first; ssh = live.second; owned = false }
        else {
            val h = store.hosts.value.firstOrNull { it.watch } ?: store.hosts.value.firstOrNull()
            if (h == null) { status = t("还没有主机"); return@LaunchedEffect }
            host = h
            val s = withContext(Dispatchers.IO) {
                val cfg = store.configFor(h, keys) ?: return@withContext null
                val sess = SshSession(cfg, KnownHosts(store, h.id, null))
                runCatching { sess.connect(); sess }.getOrNull()
            }
            if (s == null) { status = t("连不上，先在 App 里连一次这台主机"); return@LaunchedEffect }
            ssh = s; owned = true
        }
        val list = withContext(Dispatchers.IO) { runCatching { SessionProbe.snapshot(ssh!!) }.getOrNull() }
        sessions = list.orEmpty()
        if (list.isNullOrEmpty()) status = t("这台机器上没有会话")
    }

    DisposableEffect(Unit) {
        onDispose { if (owned) runCatching { ssh?.disconnect() } }
    }

    /**
     * 送进某个会话。
     *
     * ⚠️⚠️ **上传和发送要分开报，不能一个 runCatching 包住整段。**
     * 原来是 `runCatching { 传完所有文件; 发消息 }.getOrDefault(false)` —— 只要最后那一步
     * `send` 抛了，整段就是 `false`，界面只说一句「没送出去」。
     * 而**文件其实已经全传上去了**。用户 2026-09-04 分享 9 张图，看到的是「9 张全失败」，
     * 实际上服务器上 9 个文件一个不少，只是那条消息没进会话（他后来在终端里补发的）。
     *
     * ⚠️ 为什么 send 会挂：这个页面**借的是盯梢服务那条长连接**（[EventService.liveConn]）。
     * SFTP 每次开新通道所以没事，而 `exec` 用的是同一条会话 —— 那条空闲久了会被服务器收掉，
     * 表现就是 `channel is not opened`（本机日志里见过）。所以 send 失败要**换一条新连接重试**。
     */
    fun sendTo(target: Session) {
        if (sending) return
        sending = true
        scope.launch {
            val s = ssh ?: run { sending = false; return@launch }
            var uploaded = 0
            var failed = 0
            var firstErr: String? = null
            val staged = ArrayList<Attachments.Staged>()

            withContext(Dispatchers.IO) {
                if (uris.isEmpty()) return@withContext
                // ⚠️ **和对话页走同一套重试**([app.yxi.agent.Uploader]):断了等重连、换新通道、从断点续传、
                //    没进度就掐。原来这条路是**一条共用通道 + 单次尝试** —— 通道一坏,剩下的全部失败;
                //    网络抖一下,那个文件直接算失败还不续传。从分享面板甩一段视频 / 一次甩十几张图
                //    走的正是这里(审查 2026-09-06 指出这是上一轮修复漏掉的一半)。
                var live: SshSession? = s
                val alive: suspend (Long) -> SshSession? = { wait ->
                    val t0 = System.currentTimeMillis()
                    var got: SshSession? = null
                    while (true) {
                        live?.takeIf { it.isAlive }?.let { got = it; break }
                        val h = host
                        val fresh = if (h == null) null else runCatching {
                            val cfg = store.configFor(h, keys) ?: return@runCatching null
                            val sess = SshSession(cfg, KnownHosts(store, h.id, null))
                            sess.connect(); sess
                        }.getOrNull()
                        if (fresh != null) { live = fresh; got = fresh; break }
                        if (System.currentTimeMillis() - t0 >= wait) break
                        kotlinx.coroutines.delay(1000)
                    }
                    got
                }
                for (uri in uris) {
                    // ⚠️ 只收 content:。别的 App 能显式 Intent 打过来塞 `file:///data/data/app.yxi/…`，
                    //    而 openInputStream 用的是**我们自己的 UID** —— 等于替它读我们的私有文件
                    //    （confused deputy，2026-09-04 安全审计）。
                    if (uri.scheme != "content") { failed++; continue }
                    // ⚠️ **一张失败不能拖垮其余的**：每张各自算，失败只记一笔继续下一张。
                    val size = runCatching {
                        ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                    }.getOrDefault(-1L)
                    if (size <= 0L) { failed++; if (firstErr == null) firstErr = t("这个文件读不出来"); continue }
                    val mime = ctx.contentResolver.getType(uri).orEmpty()
                    val isImage = mime.startsWith("image/")
                    val name = uri.lastPathSegment?.substringAfterLast('/') ?: if (isImage) "image" else "file"
                    val stamp = java.text.SimpleDateFormat("MMdd-HHmmss-SSS", java.util.Locale.US).format(java.util.Date())
                    val one = app.yxi.agent.Uploader.upload(
                        aliveSsh = alive,
                        // 流式，不 readBytes —— 分享一段视频过来原来就是在这儿 OOM 的（Sftp.write 那条注释）
                        open = { ctx.contentResolver.openInputStream(uri) ?: error(t("这个文件读不出来")) },
                        total = size, sessionName = target.name, name = name,
                        index = staged.count { it.isImage == isImage } + 1, isImage = isImage, stamp = stamp,
                    )
                    one.getOrNull()?.let { staged += it; uploaded++ }
                        ?: run {
                            failed++
                            if (firstErr == null) {
                                firstErr = one.exceptionOrNull()?.let { app.yxi.agent.Uploader.explain(it) }
                            }
                        }
                }
            }

            val body = if (staged.isEmpty()) text.orEmpty()
            else Attachments.header(Attachments.renumber(staged)) + (text ?: t("看看这个"))

            var sent = false
            var sendErr: String? = null
            if (body.isNotBlank()) {
                withContext(Dispatchers.IO) {
                    // 先用借来的那条；挂了就**自己开一条新的**再试一次（借来的可能早被服务器收掉了）
                    // ⚠️ 用 send 的返回值，别再「没抛异常就算成功」—— 回车被粘贴块吞掉时不抛异常，
                    //    话却躺在对方输入框里（见 SessionProbe.send 里那段）。
                    sent = runCatching { SessionProbe.send(s, target.name, body) }.getOrElse {
                        sendErr = it.message
                        val h = host
                        // 自己开一条新的（跟这个页面最初连主机是同一套写法）
                        val fresh = if (h == null) null else runCatching {
                            val cfg = store.configFor(h, keys) ?: return@runCatching null
                            val sess = SshSession(cfg, KnownHosts(store, h.id, null))
                            sess.connect(); sess
                        }.getOrNull()
                        if (fresh == null) false
                        else try {
                            val ok2 = SessionProbe.send(fresh, target.name, body)
                            if (ok2) sendErr = null
                            ok2
                        } catch (e: Throwable) {
                            sendErr = e.message; false
                        } finally { runCatching { fresh.disconnect() } }
                    }
                }
            }

            // 分享进来的这条也算「自己发出去的话」——「发过的话」要的是**每一条**
            if (sent) host?.let { app.yxi.ui.SentLog.add(ctx, it.id, target.name, body) }

            // ⚠️ **如实说清到底成了几步。** 「没送出去」这四个字最坑：文件明明传上去了，
            //    用户以为要重来一遍，于是又传一遍（服务器上就有了两份）。
            val msg = when {
                sent && failed == 0 -> t("已送进 %s").format(target.short)
                sent -> t("已送进 %s —— 但有 %d 个文件没传上去（%s）").format(target.short, failed, firstErr.orEmpty())
                uploaded > 0 -> t("%d 个文件已经传上去了，但消息没发出去：%s —— 直接在 App 里发一句就行，文件不用重传")
                    .format(uploaded, sendErr ?: t("连接断了"))
                else -> t("没送出去：%s").format(firstErr ?: sendErr ?: t("连接断了"))
            }
            android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
            sending = false
            if (sent) onDone()
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(18.dp, 14.dp)) {
            Text(t("分享到哪个会话"), style = MaterialTheme.typography.headlineSmall)
            val preview = if (uris.isNotEmpty())
                t("[文件] ") + (uris.first().lastPathSegment ?: "") + (if (uris.size > 1) t("（共 %d 个）").format(uris.size) else "")
            else text.orEmpty()
            if (preview.isNotBlank()) Text(
                preview, Modifier.padding(top = 6.dp), maxLines = 2,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.outline,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            val list = sessions
            if (list == null || list.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(status, color = MaterialTheme.colorScheme.outline)
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(list, key = { it.name }) { sess ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(enabled = !sending) { sendTo(sess) },
                        ) {
                            Row(Modifier.padding(16.dp, 13.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(sess.short, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                Text(ago(sess.lastActivity), style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                }
            }
        }
    }
}
