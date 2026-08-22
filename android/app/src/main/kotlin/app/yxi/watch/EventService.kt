package app.yxi.watch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.yxi.MainActivity
import app.yxi.R
import app.yxi.ssh.Host
import app.yxi.ssh.HostStore
import app.yxi.ssh.KeyManager
import app.yxi.ssh.KnownHosts
import app.yxi.ssh.SshSession
import kotlinx.coroutines.*
import org.json.JSONObject
import kotlin.math.absoluteValue

/**
 * **手机主动响。** 常驻一条 SSH 通道 `tail -f ~/.yxi/events.jsonl`，收到就发本地通知。
 *
 * ⚠️ **不走 FCM。** 用户的荣耀 Magic7 默认关着 GMS，而开 GMS 要连国际网络 ——
 * 把一个能用的功能建在一个默认不可用的基础上，是在给自己挖坑（PRD §2.5 / 决策 D8）。
 * 前台服务 + 一条长连接，代价是通知栏常驻一条，换来的是**不依赖任何第三方**。
 *
 * ⚠️ **漏收的事件要补。** 手机没连着的时候 Claude 照样在干活，事件照样在写。
 * 所以每次连上先 `tail -n 300` 把历史捞回来，按**上次看到的时间戳**过滤 ——
 * 比「记住文件偏移」稳：文件被 hook 截断过（超 5 MB 砍前半段）偏移就废了，时间戳不会。
 */
class EventService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var store: HostStore
    private lateinit var keys: KeyManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = HostStore(applicationContext)
        keys = KeyManager(applicationContext)
        channels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val watched = store.hosts.value.filter { it.watch }
        if (watched.isEmpty()) { stopSelf(); return START_NOT_STICKY }

        Log.i("YxiWatch", "启动，盯 ${watched.size} 台：${watched.joinToString { it.alias }}")
        startForeground(ONGOING_ID, ongoing(watched.size))
        scope.coroutineContext.cancelChildren()
        watched.forEach { host -> scope.launch { watch(host) } }
        // START_STICKY：被系统杀掉后还会被拉回来（MagicOS 后台管控狠，这是最起码的）
        return START_STICKY
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    /** 盯一台机器。断了就退避重连，**永远不放弃** —— 这条服务活着的意义就是别漏事。 */
    private suspend fun watch(host: Host) {
        var wait = 2_000L
        while (currentCoroutineContext().isActive) {
            var ssh: SshSession? = null
            runCatching {
                Log.i("YxiWatch", "连 ${host.alias} …")
                val cfg = store.configFor(host, keys) ?: error("没有可用的认证方式")
                // ⚠️ 后台服务里没有 UI 可以弹「第一次连这台主机」，所以 prompt 传 null =
                // 没记过指纹的主机**连不上**。这是有意的：先在前台连一次、核对过指纹，
                // 后台才盯得住。安全上不能因为「后台没界面」就把校验放松掉。
                val known = KnownHosts(store, host.id, null)
                val s = SshSession(cfg, known); ssh = s
                s.connect()
                Log.i("YxiWatch", "${host.alias} 连上了，开始跟随事件流")
                wait = 2_000L
                stream(s, host)
            }.onFailure {
                Log.w("YxiWatch", "${host.alias} 断了：${it.message}")
            }
            runCatching { ssh?.disconnect() }
            if (!currentCoroutineContext().isActive) return
            delay(wait); wait = (wait * 2).coerceAtMost(60_000)
        }
    }

    private suspend fun stream(s: SshSession, host: Host) {
        val f = "\$HOME/.yxi/events.jsonl"
        // 先补历史再跟随。`tail -n 300 -f` 一条命令搞定，不用两次往返
        // ⚠️ `mkdir -p` 不能省：**没装 yxi-hook 的机器上根本没有 `~/.yxi/`**，
        // 那样 touch 会失败、tail 起不来、通道立刻 EOF → 无限重连（每次还要完整握手认证一遍）。
        // 建好目录之后它就只是**一条永远没有内容的流**——正是「没装就静悄悄」该有的样子。
        val shell = s.openExecStream("mkdir -p \$HOME/.yxi 2>/dev/null; touch $f 2>/dev/null; tail -n 300 -f $f")
        try {
            val reader = shell.output.bufferedReader()
            while (currentCoroutineContext().isActive) {
                val line = reader.readLine() ?: break
                runCatching { handle(JSONObject(line), host) }
            }
        } finally { shell.close() }
    }

    private fun handle(e: JSONObject, host: Host) {
        val ts = e.optDouble("ts", 0.0)
        val seen = lastSeen(host.id)
        if (ts <= seen) return                       // 补历史时把看过的滤掉
        setLastSeen(host.id, ts)

        val session = e.optString("session").removePrefix("cc-")
        val kind = e.optString("kind")
        if (kind == "end") return                    // 会话结束不值得把手机点亮

        val title = if (kind == "needs") "$session 需要你" else "$session 干完了"
        // 「需要你」的 detail 是 Claude 自己给的一句人话（"needs your permission to use Bash"），有信息量；
        // 「干完了」的 detail 是我们编的通用句，跟标题重复 —— 那就换成机器名，至少告诉你是哪台
        val text = if (kind == "needs") e.optString("detail").ifBlank { "等你决定" } else host.alias

        val open = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("hostId", host.id)
            putExtra("session", e.optString("session"))
            putExtra("cwd", e.optString("cwd"))
        }
        val pi = PendingIntent.getActivity(
            this, (host.id + session).hashCode().absoluteValue,
            open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val n = NotificationCompat.Builder(this, CH_EVENT)
            .setSmallIcon(R.drawable.ic_stat_yxi)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("${host.alias} · ${e.optString("cwd")}"))
            .setContentIntent(pi)
            .setAutoCancel(true)
            // 「需要你」要把屏幕点亮（锁屏也看得见）；「干完了」安静一点
            .setPriority(if (kind == "needs") NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(if (kind == "needs") Notification.CATEGORY_CALL else Notification.CATEGORY_STATUS)
            .build()

        runCatching {
            // 同一个会话反复响用同一个通知 id —— 通知栏里不该堆一串
            NotificationManagerCompat.from(this).notify((host.id + session).hashCode(), n)
        }.onFailure { Log.w("YxiWatch", "发通知失败（多半是没给通知权限）：${it.message}") }
    }

    private fun prefs() = getSharedPreferences("yxi", Context.MODE_PRIVATE)
    private fun lastSeen(hostId: String) = prefs().getFloat("seen:$hostId", 0f).toDouble()
    private fun setLastSeen(hostId: String, ts: Double) =
        prefs().edit().putFloat("seen:$hostId", ts.toFloat()).apply()

    private fun ongoing(n: Int) = NotificationCompat.Builder(this, CH_ONGOING)
        .setSmallIcon(R.drawable.ic_stat_yxi)
        .setContentTitle("盯着 $n 台机器")
        .setContentText("Claude 需要你时会响")
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        )
        .build()

    private fun channels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_EVENT, "Claude 找你", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "干完了 / 需要你决定" }
        )
        nm.createNotificationChannel(
            // MIN：常驻那条不该占用户的注意力，它只是系统要求的「我在后台跑」的凭证
            NotificationChannel(CH_ONGOING, "后台盯梢", NotificationManager.IMPORTANCE_MIN)
        )
    }

    companion object {
        private const val CH_EVENT = "yxi.events"
        private const val CH_ONGOING = "yxi.ongoing"
        private const val ONGOING_ID = 1

        /** 有主机被勾选就起，全都取消就停。调用方不用自己判断。 */
        fun sync(ctx: Context, anyWatched: Boolean) {
            val i = Intent(ctx, EventService::class.java)
            if (anyWatched) ContextCompat_startForegroundService(ctx, i) else ctx.stopService(i)
        }

        private fun ContextCompat_startForegroundService(ctx: Context, i: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }
    }
}
