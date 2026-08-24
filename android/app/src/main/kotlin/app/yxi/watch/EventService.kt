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
import app.yxi.agent.Pending
import app.yxi.agent.SessionProbe
import app.yxi.ui.Pinned
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

    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** 活着的连接，按 hostId。点通知按钮时要用它送键，不能为此再连一次。 */
    private val live = java.util.concurrent.ConcurrentHashMap<String, SshSession>()
    private lateinit var store: HostStore
    private lateinit var keys: KeyManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        store = HostStore(applicationContext)
        keys = KeyManager(applicationContext)
        channels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ⚠️ 必须先 reload：界面那份 HostStore 是另一个实例，改了开关这边不知道
        store.reload()
        val watched = store.hosts.value.filter { it.watch }
        if (watched.isEmpty()) { stopSelf(); return START_NOT_STICKY }

        Log.i("YxiWatch", "启动，盯 ${watched.size} 台：${watched.joinToString { it.alias }}")
        startForeground(ONGOING_ID, ongoing(watched.size))
        scope.coroutineContext.cancelChildren()
        watched.forEach { host -> scope.launch { watch(host) } }
        // START_STICKY：被系统杀掉后还会被拉回来（MagicOS 后台管控狠，这是最起码的）
        return START_STICKY
    }

    override fun onDestroy() { instance = null; scope.cancel(); super.onDestroy() }

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
                live[host.id] = s
                Log.i("YxiWatch", "${host.alias} 连上了，开始跟随事件流")
                wait = 2_000L
                stream(s, host)
            }.onFailure {
                Log.w("YxiWatch", "${host.alias}（${host.display}）断了：${it.message}")
            }
            live.remove(host.id)
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
        val prep = "mkdir -p \$HOME/.yxi 2>/dev/null; touch $f 2>/dev/null; "
        val shell = s.openExecStream(prep + SshSession.follow("tail -n 300 -f $f"))
        // ⚠️ 取消协程不会打断阻塞在 readLine() 上的线程（那不是挂起点）。
        // 不主动关通道的话，关掉铃铛之后这条通道和远端的 tail 都还活着。
        val onCancel = currentCoroutineContext()[kotlinx.coroutines.Job]
            ?.invokeOnCompletion { runCatching { shell.close() } }
        try {
            val reader = shell.output.bufferedReader()
            while (currentCoroutineContext().isActive) {
                val line = reader.readLine() ?: break
                runCatching { handle(JSONObject(line), host, s) }
            }
        } finally {
            onCancel?.dispose()
            shell.close()
        }
    }

    private suspend fun handle(e: JSONObject, host: Host, ssh: SshSession) {
        val ts = e.optDouble("ts", 0.0)
        val seen = lastSeen(host.id)
        if (ts <= seen) return                       // 补历史时把看过的滤掉
        setLastSeen(host.id, ts)

        val full = e.optString("session")
        val session = full.removePrefix("cc-")
        val kind = e.optString("kind")
        if (kind == "end") return                    // 会话结束不值得把手机点亮

        // 只通知置顶的会话。
        //
        // ⚠️ **比的是带 `cc-` 前缀的全名。** 置顶存的是 `Session.name`（`cc-Yxi`），
        // 事件里的 `session` 字段也是全名；而上面那个 `session` 变量是**去了前缀的短名**，
        // 拿它来比会一条都对不上 —— 表现是「打开这个开关之后彻底没通知了」，
        // 而且没有任何报错。用 [full]。
        //
        // ⚠️ **一条都没置顶时不生效**，见 [Pinned.onlyPinned] 的注释。
        if (!Pinned.shouldNotify(Pinned.onlyPinned(this), Pinned.get(this, host.id), full)) return

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

        // ⚠️ **「需要你」的时候，按钮上的选项一律从屏幕上读，绝不预设。**
        // 权限提示的三个选项是 `1. Yes` / `2. Yes, and don\u2019t ask again for: …` / `3. No` ——
        // 把「拒绝」硬编码成 2 的话，点一下就是**永久放行这一类命令**。
        // 读不出来（解析失败 / 抓屏失败）就**不给按钮**，只留「点开去看」。宁可多一步，不能点错。
        val pending: Pending? =
            if (kind == "needs" && full.isNotBlank())
                runCatching { SessionProbe.pending(ssh, full) }.getOrNull()
            else null

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
            // 锁屏上要看得见内容 —— 看不见就没法判断，「锁屏批权限」也就无从谈起。
            // 代价是命令文本会显示在锁屏上，这是个自觉的取舍。
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .also { b ->
                pending?.options?.take(3)?.forEach { o ->
                    b.addAction(0, "${o.number}. ${o.label.take(16)}", answerIntent(host, full, o, pending.fingerprint))
                }
                pending?.title?.takeIf { it.isNotBlank() }?.let {
                    b.setStyle(NotificationCompat.BigTextStyle().bigText("${host.alias}\n$it"))
                }
            }
            .build()

        runCatching {
            // 同一个会话反复响用同一个通知 id —— 通知栏里不该堆一串
            NotificationManagerCompat.from(this).notify((host.id + session).hashCode(), n)
        }.onFailure { Log.w("YxiWatch", "发通知失败（多半是没给通知权限）：${it.message}") }
    }

    /**
     * ⚠️ **必须用 `getBroadcast`，不能用 `getService`。**
     * targetSdk 34+ 之后从后台启动服务被限制，通知按钮上的 `PendingIntent.getService`
     * 会被**静默挡掉** —— 点了完全没反应，logcat 里也**一条日志都没有**，
     * 最难查的那种。广播才是通知动作的标准做法。
     */
    private fun answerIntent(host: Host, session: String, o: Pending.Option, fp: String): PendingIntent {
        val i = Intent(this, AnswerReceiver::class.java)
            .setAction(ACT_ANSWER)
            .putExtra("hostId", host.id)
            .putExtra("session", session)
            .putExtra("number", o.number)
            .putExtra("label", o.label)
            .putExtra("fp", fp)
        return PendingIntent.getBroadcast(
            this, (host.id + session + o.number).hashCode().absoluteValue,
            i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * 在通知上点了某个选项。
     *
     * ⚠️ **送键之前必须重新抓一次屏确认。** 从发通知到你按下按钮，中间可能过了几分钟 ——
     * 那个提示可能已经被别处答掉了，屏幕上换成了**另一个**提示。
     * 那时候把当初那个号码送进去，就是在**回答一个你根本没看见的问题**。
     * 所以号码和文案都得跟当初一致才送；对不上就什么都不做，只提示你去看看。
     */
    internal suspend fun answer(i: Intent) {
        val hostId = i.getStringExtra("hostId") ?: return
        val session = i.getStringExtra("session") ?: return
        val number = i.getIntExtra("number", -1)
        val label = i.getStringExtra("label").orEmpty()
        val fp = i.getStringExtra("fp").orEmpty()
        val s = live[hostId] ?: run { note("连接不在了，没送出去"); return }

        val now = runCatching { SessionProbe.pending(s, session) }.getOrNull()
        // ⚠️ **整块指纹必须一致，不能只比选项。** 两个不同的权限提示选项完全一样
        // （都是 `1. Yes / 2. Yes, and always… / 3. No`），只比选项等于没比 ——
        // 你以为在批 A，实际批的是屏幕上换成的 B。
        val same = now != null &&
            now.fingerprint == fp &&
            now.options.any { it.number == number && it.label == label }
        if (!same) { note("提示变了，没有替你按 —— 点开看看"); return }

        val ok = runCatching { SessionProbe.sendKey(s, session, number.toString()) }.getOrDefault(false)
        if (ok) runCatching {
            NotificationManagerCompat.from(this).cancel((hostId + session.removePrefix("cc-")).hashCode())
        } else note("送不出去，没批")
    }

    /** 用一条通知代替 toast —— 服务里 toast 在新版安卓上不一定弹得出来。 */
    private fun note(text: String) = runCatching {
        NotificationManagerCompat.from(this).notify(
            9_001,
            NotificationCompat.Builder(this, CH_EVENT)
                .setSmallIcon(R.drawable.ic_stat_yxi)
                .setContentTitle(text)
                .setAutoCancel(true)
                .build(),
        )
    }.let { }

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
        internal const val ACT_ANSWER = "app.yxi.ANSWER"

        /** 广播接收器要够到活着的这个服务实例。同进程单例，直接引用最省事。 */
        @Volatile internal var instance: EventService? = null

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
