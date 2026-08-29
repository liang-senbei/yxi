package app.yxi.watch

import app.yxi.ui.Risky
import app.yxi.ui.t
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import app.yxi.MainActivity
import app.yxi.R
import app.yxi.ssh.Host
import app.yxi.ssh.HostStore
import app.yxi.ssh.KeyManager
import app.yxi.ssh.KnownHosts
import app.yxi.agent.Pending
import app.yxi.agent.SessionProbe
import app.yxi.agent.SessionState
import app.yxi.ui.Pinned
import app.yxi.ui.Mute
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
        // ⚠️ 服务可能比界面先起来（开机自启、被系统拉起）。不在这儿读一次的话，
        // 通知会**先弹几条中文**再跟上 —— 那种不一致比全中文更让人困惑。
        app.yxi.ui.I18n.load(applicationContext)
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
        scope.launch { escalate() }   // 升级重提醒：等太久没答就再响一次
        scope.launch { statusLoop() }  // 数「在跑」的会话，喂常驻通知/磁贴/小组件
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
                val cfg = store.configFor(host, keys) ?: error(t("没有可用的认证方式"))
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
        // 这个会话被单独静音了就别响 —— 置顶的反面（在会话卡长按 / 通知上「静音」都能设）
        if (Mute.isMuted(this, host.id, full)) return

        val detail = e.optString("detail")       // 钩子那条「为什么找你」（Notification message）
        val preview = e.optString("preview")      // Claude 最后说的一句（钩子从转录里取，零 token）——「到底要你决定什么」
        // PermissionRequest 带来的结构化信息：哪个工具、要动什么。
        // ⚠️ 用它判「能不能一键批」比在中文里正则找 `rm -rf` 靠谱得多
        val tool = e.optString("tool")
        val arg = e.optString("arg")
        // 常驻通知要跟着变 —— 胶囊上显示的就是它。顺便记下「从什么时候开始等」，给升级重提醒用
        if (kind == "needs") {
            waiting += session
            waits.getOrPut(session) { WaitCtx(host, full, session, e.optString("cwd"), System.currentTimeMillis()) }
                .also { it.detail = detail; it.preview = preview }   // 同一会话来新事件就刷新
        } else {
            waiting -= session; waits.remove(session)
        }
        refreshOngoing()

        if (kind == "needs") {
            // 选项一律从屏幕读，绝不预设（把「拒绝」写死成 2 = 点一下就永久放行）；读不出就只留「点开去看」
            val pending = if (full.isNotBlank()) runCatching { SessionProbe.pending(ssh, full) }.getOrNull() else null
            postNeeds(host, full, session, e.optString("cwd"), pending, mins = 0, alert = true,
                      detail = detail, preview = preview, tool = tool, arg = arg)
        } else {
            postDone(host, full, session, e.optString("cwd"), preview)
        }
    }

    /** 「从什么时候开始等你」——给「已等你 Xm」和升级重提醒用；detail/preview 是「要你决定什么」的两个来源。 */
    private class WaitCtx(
        val host: Host, val full: String, val short: String, val cwd: String, val since: Long,
        @Volatile var detail: String = "", @Volatile var preview: String = "",
    ) { @Volatile var alerted: Int = 0 }
    private val waits = java.util.concurrent.ConcurrentHashMap<String, WaitCtx>()

    /**
     * 「需要你」的通知 —— 纯构建，不碰 SSH（pending 由调用方抓好传进来）。
     * 决策按钮（从屏幕读的选项）+ **回一句**（自由文本，顺带白送语音：RemoteInput 会露出输入法麦克风）+ **静音**。
     * @param alert true = 要响（首发 / 升级重提醒）；false = 只更新不打扰
     */
    private fun postNeeds(host: Host, full: String, short: String, cwd: String, pending: Pending?, mins: Int, alert: Boolean, detail: String = "", preview: String = "", tool: String = "", arg: String = "") {
        val open = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("hostId", host.id); putExtra("session", full); putExtra("cwd", cwd)
        }
        val pi = PendingIntent.getActivity(
            this, (host.id + short).hashCode().absoluteValue,
            open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val waitLine = if (mins >= 1) t("已等你 %d 分钟").format(mins) else t("等你决定")
        // 「到底要你决定什么」—— 全是现成的文字，零 token：屏幕上的提示 > Claude 最后说的话 > 钩子那句 > 兜底
        val promptTitle = pending?.title?.takeIf { it.isNotBlank() }
        val what = promptTitle ?: preview.takeIf { it.isNotBlank() } ?: detail.takeIf { it.isNotBlank() } ?: waitLine
        val b = NotificationCompat.Builder(this, CH_NEEDS)
            .setSmallIcon(R.drawable.ic_stat_yxi)
            .setContentTitle(t("%s 需要你").format(short))
            .setContentText(what)                     // 折叠时就看得到「要你决定什么」，不再只是「等你决定」
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setOnlyAlertOnce(!alert)                 // 升级重提醒 alert=true → 再响一次
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        // **锁屏上把屏幕点亮。**
        //
        // ⚠️ 光有 PRIORITY_HIGH 不够：手机躺在桌上黑着屏时，普通高优先级通知
        // 只是进列表，屏幕不一定亮 —— 而这个 App 的全部意义就是「你不在电脑前也能被叫住」。
        // fullScreenIntent 才会点亮屏幕并把内容摆到锁屏上。
        //
        // ⚠️ **必须能降级。** Android 14 起系统默认不把这个权限给非通话/闹钟类应用，
        // 拿不到就当没有这行 —— 退回普通高优先级通知，跟以前一个样，不能因此崩或者不发。
        // ⚠️ 第二个参数传 true = 允许系统在用户正在用手机时改成「悬浮条」而不是全屏接管。
        // 传 false 会在你正打字时糊你一脸全屏，那比不提醒还讨厌。
        if (canFullScreen()) b.setFullScreenIntent(pi, true)
        // ⚠️ **危险动作不给一键批。**
        //
        // 我们原来只防「提示变了」（#53 加指纹校验）—— 那是**机器侧的过期**。
        // 没防的是**人麻木了**：锁屏上连点几次「批准」是肌肉记忆，
        // 而事故靠的从来不是绕过校验，是「你正忙着，顺手点了」。
        // 业界叫这个「批准疲劳」。
        //
        // 所以 `rm -rf` / force-push / 动 `authorized_keys` 这类**不给按钮**，
        // 只留「回一句」和「静音」—— 想批就得点开看清楚。
        // ⚠️ 真踩过：脚本把用户手机的公钥从 authorized_keys 里删了（#65）。
        // 那种操作批错了，你连补救都进不去。
        val oneTap = Risky.oneTapOk(tool, arg) && !Risky.matches(what)
        if (oneTap) {
            pending?.options?.take(3)?.forEach { o ->
                b.addAction(0, "${o.number}. ${o.label.take(16)}", answerIntent(host, full, o, pending.fingerprint))
            }
        }
        b.addAction(replyAction(host, full))
        b.addAction(0, t("静音"), muteIntent(host, full, short))
        // 展开：Claude 最后说的 + 屏幕提示 + 位置 + 等待时长，凑齐上下文
        b.setStyle(NotificationCompat.BigTextStyle().bigText(
            listOfNotNull(
                preview.takeIf { it.isNotBlank() },
                promptTitle?.takeIf { it != preview },
                detail.takeIf { it.isNotBlank() && it != preview && it != promptTitle },
                "${host.alias} · $cwd",
                if (mins >= 1) waitLine else null,
            ).joinToString("\n").ifBlank { what }))
        runCatching { NotificationManagerCompat.from(this).notify((host.id + short).hashCode(), b.build()) }
            .onFailure { Log.w("YxiWatch", "发通知失败（多半是没给通知权限）：${it.message}") }
    }

    /** 「干完了」的通知 —— 安静一点，但也能**直接回一句**（以前是死胡同）和静音。 */
    private fun postDone(host: Host, full: String, short: String, cwd: String, preview: String = "") {
        val open = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("hostId", host.id); putExtra("session", full); putExtra("cwd", cwd)
        }
        val pi = PendingIntent.getActivity(
            this, (host.id + short).hashCode().absoluteValue,
            open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // 干完了也顺手告诉你 Claude 最后说了啥（零 token，从转录取）
        val line = preview.takeIf { it.isNotBlank() } ?: host.alias
        val b = NotificationCompat.Builder(this, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat_yxi)
            .setContentTitle(t("%s 干完了").format(short))
            .setContentText(line)
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                listOfNotNull(preview.takeIf { it.isNotBlank() }, "${host.alias} · $cwd").joinToString("\n")))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        b.addAction(replyAction(host, full))
        b.addAction(0, t("静音"), muteIntent(host, full, short))
        runCatching { NotificationManagerCompat.from(this).notify((host.id + short).hashCode(), b.build()) }
            .onFailure { Log.w("YxiWatch", "发通知失败：${it.message}") }
    }

    /** 通知上的「回一句」—— RemoteInput 自由文本。⚠️ PendingIntent 必须 MUTABLE，系统才能把回复塞进去。 */
    private fun replyAction(host: Host, full: String): NotificationCompat.Action {
        val ri = RemoteInput.Builder(KEY_REPLY).setLabel(t("回一句…")).build()
        val i = Intent(this, AnswerReceiver::class.java).setAction(ACT_REPLY)
            .putExtra("hostId", host.id).putExtra("session", full)
        val pi = PendingIntent.getBroadcast(
            this, (host.id + full + "reply").hashCode().absoluteValue, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        return NotificationCompat.Action.Builder(0, t("回一句"), pi)
            .addRemoteInput(ri).setAllowGeneratedReplies(true).build()
    }

    private fun muteIntent(host: Host, full: String, short: String): PendingIntent {
        val i = Intent(this, AnswerReceiver::class.java).setAction(ACT_MUTE)
            .putExtra("hostId", host.id).putExtra("session", full).putExtra("short", short)
        return PendingIntent.getBroadcast(
            this, (host.id + full + "mute").hashCode().absoluteValue, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** 在通知上打字回了一句 —— 直接送进那个会话（顺带覆盖语音，输入法麦克风就是 RemoteInput 的一部分）。 */
    internal suspend fun reply(i: Intent) {
        val hostId = i.getStringExtra("hostId") ?: return
        val full = i.getStringExtra("session") ?: return
        val text = RemoteInput.getResultsFromIntent(i)?.getCharSequence(KEY_REPLY)?.toString()?.trim().orEmpty()
        if (text.isBlank()) return
        val s = live[hostId] ?: run { note(t("连接不在了，没送出去")); return }
        val ok = runCatching { SessionProbe.send(s, full, text); true }.getOrDefault(false)
        val short = full.removePrefix("cc-")
        if (ok) {
            waiting -= short; waits.remove(short); refreshOngoing()
            runCatching { NotificationManagerCompat.from(this).cancel((hostId + short).hashCode()) }
        } else note(t("送不出去，没批"))
    }

    /** 在通知上点了「静音」—— 把这个会话加进静音名单，清掉它的通知。 */
    internal fun mute(i: Intent) {
        val hostId = i.getStringExtra("hostId") ?: return
        val full = i.getStringExtra("session") ?: return
        val short = i.getStringExtra("short") ?: full.removePrefix("cc-")
        Mute.toggle(this, hostId, full)
        waiting -= short; waits.remove(short); refreshOngoing()
        runCatching { NotificationManagerCompat.from(this).cancel((hostId + short).hashCode()) }
        note(t("已静音 %s —— 之后不再提醒（会话卡长按可取消）").format(short))
    }

    /**
     * 升级重提醒 —— 每分钟看一眼还在等你的会话，跨过 2/5/10/20/40 分钟就再响一次并更新「已等你 Xm」。
     * ⚠️ 抓不到 pending 就等下一轮，**绝不主动取消通知**（可能只是抓屏时机没赶上）——
     * 真答掉了，事件流随后会来 done / 下一个 needs，[handle] 自会清掉。
     */
    /**
     * 轻量状态轮询 —— 每 30 秒快照一次，数「在跑」的会话，喂给常驻通知 / 磁贴 / 小组件。
     * ⚠️ 30 秒而不是像看板那样 5 秒：后台要省电（荣耀本来就狠管后台），够「瞄一眼」就行。
     */
    private suspend fun statusLoop() {
        while (currentCoroutineContext().isActive) {
            var w = 0
            for ((_, s) in live) {
                val snap = runCatching { SessionProbe.snapshot(s) }.getOrNull() ?: continue
                w += snap.count { it.state == SessionState.Working }
            }
            workingCount = w
            refreshOngoing()
            delay(30_000)
        }
    }

    private suspend fun escalate() {
        val thresh = intArrayOf(2, 5, 10, 20, 40)
        while (currentCoroutineContext().isActive) {
            delay(60_000)
            for ((short, wc) in waits.entries.toList()) {
                if (short !in waiting) { waits.remove(short); continue }
                val mins = ((System.currentTimeMillis() - wc.since) / 60_000L).toInt()
                val crossed = thresh.lastOrNull { it <= mins && it > wc.alerted } ?: continue
                val s = live[wc.host.id] ?: continue
                val pending = runCatching { SessionProbe.pending(s, wc.full) }.getOrNull() ?: continue
                wc.alerted = crossed
                postNeeds(wc.host, wc.full, short, wc.cwd, pending, mins, alert = true, detail = wc.detail, preview = wc.preview)
            }
        }
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
        val s = live[hostId] ?: run { note(t("连接不在了，没送出去")); return }

        val now = runCatching { SessionProbe.pending(s, session) }.getOrNull()
        // ⚠️ **整块指纹必须一致，不能只比选项。** 两个不同的权限提示选项完全一样
        // （都是 `1. Yes / 2. Yes, and always… / 3. No`），只比选项等于没比 ——
        // 你以为在批 A，实际批的是屏幕上换成的 B。
        val same = now != null &&
            now.fingerprint == fp &&
            now.options.any { it.number == number && it.label == label }
        if (!same) { note(t("提示变了，没有替你按 —— 点开看看")); return }

        val ok = runCatching { SessionProbe.sendKey(s, session, number.toString()) }.getOrDefault(false)
        if (ok) runCatching {
            waiting -= session.removePrefix("cc-")
            refreshOngoing()
            NotificationManagerCompat.from(this).cancel((hostId + session.removePrefix("cc-")).hashCode())
        } else note(t("送不出去，没批"))
    }

    /** 用一条通知代替 toast —— 服务里 toast 在新版安卓上不一定弹得出来。 */
    private fun note(text: String) = runCatching {
        NotificationManagerCompat.from(this).notify(
            9_001,
            NotificationCompat.Builder(this, CH_DONE)
                .setSmallIcon(R.drawable.ic_stat_yxi)
                .setContentTitle(text)
                .setAutoCancel(true)
                .build(),
        )
    }.let { }

    /**
     * 此刻有哪些会话在等你。**常驻通知（也就是灵动胶囊要显示的那条）靠它。**
     *
     * ⚠️ 用并发集合：`handle()` 在事件流协程里写，通知按钮的广播在主线程写。
     */
    private val waiting = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    /** 此刻有几个会话在真跑（由 [statusLoop] 每 30 秒快照一次）。 */
    @Volatile private var workingCount = 0

    /**
     * 把常驻那条重画一遍。
     *
     * ⚠️ **用同一个 id `notify()` 就能改前台通知**，不用重新 `startForeground()` ——
     * 后者在新版安卓上有一堆前台服务类型的限制，能不碰就别碰。
     */
    private fun refreshOngoing() = runCatching {
        NotificationManagerCompat.from(this).notify(ONGOING_ID, ongoing(store.hosts.value.count { it.watch }))
        // 磁贴 / 桌面小组件没有连接，只能读这份「上一次已知态」
        prefs().edit()
            .putInt("waitingCount", waiting.size)
            .putString("waitingNames", waiting.toList().sorted().joinToString("、"))
            .putInt("workingCount", workingCount)
            .apply()
        app.yxi.widget.WaitingWidget.refresh(this)
        android.service.quicksettings.TileService.requestListeningState(
            this, android.content.ComponentName(this, app.yxi.widget.WaitingTile::class.java))
    }.let { }

    private fun prefs() = getSharedPreferences("yxi", Context.MODE_PRIVATE)
    private fun lastSeen(hostId: String) = prefs().getFloat("seen:$hostId", 0f).toDouble()
    private fun setLastSeen(hostId: String, ts: Double) =
        prefs().edit().putFloat("seen:$hostId", ts.toFloat()).apply()

    /**
     * 常驻那条通知。**它同时是「灵动岛 / 灵动胶囊」上显示的那条**（见 [promote]）。
     *
     * 内容跟着 [waiting] 变：没人等你时它就安静地说「盯着 N 台机器」；
     * 一旦有会话在等，就换成「N 个会话等你」并请求提升 ——
     * 胶囊的价值全在这一下：**不用解锁、不用点开，扫一眼就知道要不要管。**
     */
    private fun ongoing(n: Int): Notification {
        val who = waiting.toList().sorted()
        val b = NotificationCompat.Builder(this, CH_ONGOING)
            .setSmallIcon(R.drawable.ic_stat_yxi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
        if (who.isEmpty()) {
            if (workingCount > 0)
                b.setContentTitle(t("%d 个会话在跑").format(workingCount)).setContentText(t("Claude 正在干活"))
            else
                b.setContentTitle(t("盯着 %d 台机器").format(n)).setContentText(t("Claude 需要你时会响"))
        } else {
            b.setContentTitle(t("%d 个会话等你").format(who.size))
                .setContentText(if (workingCount > 0) who.joinToString("、") + t(" · %d 在跑").format(workingCount)
                else who.joinToString("、"))
                // 有事的时候才上色：颜色是提示，天天亮着就不是提示了
                .setColorized(true)
                .setColor(0xFFE08B57.toInt())
            promote(b)
        }
        return b.build()
    }

    /**
     * 请系统把这条常驻通知提升成「实时活动」——
     * 原生安卓显示成状态栏胶囊，各家 ROM 的灵动岛 / 灵动胶囊也吃这一套。
     *
     * ⚠️ **这是「请求」不是「命令」。** 系统会自己判断够不够格
     * （`Notification.hasPromotableCharacteristics()`），不够就当没看见 ——
     * 不报错、不抛异常、什么都不发生。所以 [app.yxi.ui.DevMode] 里加了一项，
     * **发完之后回头去查系统有没有真的给** `FLAG_PROMOTED_ONGOING`。
     * 光看代码永远不知道这事成没成。
     *
     * ⚠️ 常量是编译期内联的 `String` / `Int`，所以老系统上也不会因为找不到类而崩；
     * 老系统只是不认识这个 extra，忽略掉而已。
     */
    private fun promote(b: NotificationCompat.Builder) {
        b.addExtras(Bundle().apply {
            putBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING, true)
        })
    }

    /**
     * 系统给不给我们「点亮屏幕」这个权限。
     *
     * ⚠️ Android 14（API 34）起，`USE_FULL_SCREEN_INTENT` 从装上就有变成了
     * **只默认给通话和闹钟类应用**，别的应用要用户去设置里单独开。
     * 所以这里必须问一次再用 —— 问都不问直接调，在新系统上等于这行代码不存在，
     * 而你还以为自己做了锁屏唤醒。
     */
    private fun canFullScreen(): Boolean =
        if (Build.VERSION.SDK_INT < 34) true
        else runCatching {
            getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        }.getOrDefault(false)

    private fun channels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        // 拆成两条频道：不只是分类，更是给「触感词汇表」和「单独静音 / 免打扰」留的抓手 ——
        // 系统里能分别调「Claude 找你」和「干完了」。震动写在频道上（频道创建后改不动，所以是新 id）。
        nm.createNotificationChannel(
            NotificationChannel(CH_NEEDS, t("Claude 找你"), NotificationManager.IMPORTANCE_HIGH).apply {
                description = t("需要你决定")
                enableVibration(true); vibrationPattern = longArrayOf(0, 55, 65, 55)   // 急促两下 = 该管了
                // ⚠️ **频道上也要放开。** Android 8 起频道的锁屏可见性会盖过单条通知的
                // `setVisibility` —— 只在通知上设的话，锁屏可能只显示「内容已隐藏」，
                // 而「一眼看清它要批什么」正是这条通知存在的理由。
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_DONE, t("干完了"), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = t("会话干完了")
                enableVibration(true); vibrationPattern = longArrayOf(0, 28)            // 轻轻一下 = 完事了
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        nm.createNotificationChannel(
            // MIN：常驻那条不该占用户的注意力，它只是系统要求的「我在后台跑」的凭证
            NotificationChannel(CH_ONGOING, t("后台盯梢"), NotificationManager.IMPORTANCE_MIN)
        )
        // 老的合并频道退休 —— 它的震动/重要级改不动，换成上面 needs/done 两条
        runCatching { nm.deleteNotificationChannel("yxi.events") }
    }

    companion object {
        private const val CH_NEEDS = "yxi.needs"
        private const val CH_DONE = "yxi.done"
        private const val CH_ONGOING = "yxi.ongoing"
        private const val ONGOING_ID = 1
        internal const val ACT_ANSWER = "app.yxi.ANSWER"
        internal const val ACT_REPLY = "app.yxi.REPLY"
        internal const val ACT_MUTE = "app.yxi.MUTE"
        internal const val KEY_REPLY = "reply_text"

        /** 广播接收器要够到活着的这个服务实例。同进程单例，直接引用最省事。 */
        @Volatile internal var instance: EventService? = null

        /** 分享到会话用：借盯梢服务已经建好的那条连接，省得再连一次（连不上就 null，调用方自己新建）。 */
        internal fun liveConn(): Pair<Host, SshSession>? {
            val svc = instance ?: return null
            val (hid, s) = svc.live.entries.firstOrNull() ?: return null
            val host = svc.store.hosts.value.firstOrNull { it.id == hid } ?: return null
            return host to s
        }

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
