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
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)
        val uri: Uri? =
            if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent?.getParcelableExtra(Intent.EXTRA_STREAM)
        val store = HostStore(applicationContext)
        val keys = KeyManager(applicationContext)
        setContent { YxiTheme { SharePicker(store, keys, text, uri) { finish() } } }
    }
}

@Composable
private fun SharePicker(store: HostStore, keys: KeyManager, text: String?, uri: Uri?, onDone: () -> Unit) {
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

    fun sendTo(target: Session) {
        if (sending) return
        sending = true
        scope.launch {
            val s = ssh ?: return@launch
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    if (uri != null) {
                        // 图/文件：SFTP 传上去，正文贴路径映射（跟 App 里附件一个路子）
                        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching false
                        val mime = ctx.contentResolver.getType(uri).orEmpty()
                        val isImage = mime.startsWith("image/")
                        val name = uri.lastPathSegment?.substringAfterLast('/') ?: if (isImage) "image" else "file"
                        val stamp = java.text.SimpleDateFormat("MMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
                        val sftp = s.openSftp()
                        val staged = try { Attachments.upload(sftp, target.name, name, bytes, 1, isImage, stamp) }
                        finally { runCatching { sftp.close() } }
                        SessionProbe.send(s, target.name, Attachments.header(listOf(staged)) + (text ?: t("看看这个")))
                        true
                    } else if (!text.isNullOrBlank()) {
                        SessionProbe.send(s, target.name, text)
                        true
                    } else false
                }.getOrDefault(false)
            }
            android.widget.Toast.makeText(
                ctx, if (ok) t("已送进 %s").format(target.short) else t("没送出去"),
                android.widget.Toast.LENGTH_SHORT,
            ).show()
            onDone()
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(18.dp, 14.dp)) {
            Text(t("分享到哪个会话"), style = MaterialTheme.typography.headlineSmall)
            val preview = uri?.let { t("[文件] ") + (it.lastPathSegment ?: "") } ?: text.orEmpty()
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
