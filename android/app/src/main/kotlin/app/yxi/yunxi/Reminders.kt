package app.yxi.yunxi

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import app.yxi.MainActivity
import app.yxi.R
import app.yxi.ui.I18n
import app.yxi.ui.t
import java.time.Instant
import java.time.ZoneId

/**
 * 云曦小管家 · 闹钟。备忘带了 `remindAt` 就在这儿排一个 [AlarmManager] 闹钟，到点 [ReminderReceiver] 发通知。
 *
 * - 用 `setAlarmClock`：穿透 Doze、OEM 最难杀、状态栏有闹钟图标 —— 「提醒」就该是这个待遇。
 *   Android 12+ 要精确闹钟权限：13+ 走 `USE_EXACT_ALARM`（闹钟类免申请；我们走侧载，不过 Play 审核），
 *   12 走 `SCHEDULE_EXACT_ALARM`（默认已授）。万一被用户在系统里关了（[canExact] 为 false），
 *   退化成 `setAndAllowWhileIdle` —— 可能晚几分钟，界面可以用 [exactSettingsIntent] 引导去开。
 * - 一条备忘一个 PendingIntent，`data` 用 `yunxi://memo/<id>` 区分 —— 取消时按 Intent 匹配，
 *   光靠 requestCode（hashCode）会撞。
 * - 重启 / 升级后系统会清掉所有闹钟，[ReminderReceiver] 收 BOOT_COMPLETED / MY_PACKAGE_REPLACED 重排。
 *
 * ⚠️ 通知文案走 [t]，接收器可能在冷进程里跑，先 `I18n.load(ctx)`，不然英文用户先看到一条中文。
 */
object Reminders {
    const val CHANNEL = "yunxi.remind"
    internal const val ACTION_FIRE = "app.yxi.yunxi.FIRE"
    internal const val EXTRA_ID = "id"

    private fun am(ctx: Context) = ctx.getSystemService(AlarmManager::class.java)

    /** Android 12+ 能不能排精确闹钟；false 时闹钟可能晚几分钟 */
    fun canExact(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am(ctx).canScheduleExactAlarms()

    /** 引导去系统「闹钟和提醒」开关的 Intent；<12 没这一说，返回 null */
    fun exactSettingsIntent(ctx: Context): Intent? =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) null
        else Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}"))

    /** 通知总开关开着没（13+ 要用户授 POST_NOTIFICATIONS；关着的话到点了也悄无声息） */
    fun notificationsEnabled(ctx: Context): Boolean =
        ctx.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    /** 开机 / 升级后把还没响的全排回去。[Memos] 内部按需调，界面一般不用管。 */
    fun rescheduleAll(ctx: Context) {
        Memos.load(ctx)
        val now = System.currentTimeMillis()
        Memos.list.forEach { if (it.pending(now)) schedule(ctx, it) }
    }

    internal fun schedule(ctx: Context, m: Memo) {
        val at = m.remindAt ?: return
        val now = System.currentTimeMillis()
        // 重复的、时间已过（比如关机错过了）：直接排到下一次，不补响
        val fireAt = if (at > now) at else nextAfter(m, now) ?: at
        // 不重复的、时间已过、还没响过：`setAlarmClock` 给个过去的时刻会立刻响 —— 晚响好过不响
        val pi = firePi(ctx, m.id)
        val a = am(ctx)
        runCatching {
            if (canExact(ctx)) a.setAlarmClock(AlarmManager.AlarmClockInfo(fireAt, openPi(ctx)), pi)
            else a.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pi)
        }.onFailure {
            // SecurityException：权限在我们检查之后被收走了。退化到不精确的，别让它整个不响
            runCatching { a.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pi) }
        }
    }

    internal fun cancel(ctx: Context, id: String) {
        runCatching { am(ctx).cancel(firePi(ctx, id)) }
    }

    /** 到点了：发通知；重复的把下一次写回去（[Memos.markFired] 里顺手重排） */
    internal fun fire(ctx: Context, id: String) {
        I18n.load(ctx)
        Memos.load(ctx)
        val m = Memos.get(id) ?: return
        if (m.done) return
        ensureChannel(ctx)
        val body = m.text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_yxi)
            .setContentTitle(t("云曦提醒你"))
            .setContentText(body.take(120))
            .setStyle(Notification.BigTextStyle().bigText(m.text.take(600)))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openPi(ctx))
            .setShowWhen(true)
            .build()
        // 通知 id 用 uuid 的 hashCode：≤200 条备忘撞哈希的概率约 5e-6，而且只有两条**同时在通知栏里**才会互相盖，
        // 不值得为它给 Memo 加自增字段。PendingIntent 的唯一性不靠它 —— 靠 firePi 里的 data URI。（两轮审查都提过，结论一致）
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(id.hashCode(), n) }
        val now = System.currentTimeMillis()
        Memos.markFired(ctx, id, nextAfter(m, now), now)
    }

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, t("云曦提醒你"), NotificationManager.IMPORTANCE_HIGH).apply {
                description = t("备忘到点时响")
                enableVibration(true); vibrationPattern = longArrayOf(0, 60, 80, 60)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
    }

    /**
     * 下一次响的时刻（严格晚于 [now]）；不重复的返回 null。
     * 用 java.time 按**墙上时间**加一天 / 一周，不是 +86400000 —— 有夏令时的地区加毫秒会漂一小时。
     */
    internal fun nextAfter(m: Memo, now: Long): Long? {
        val at = m.remindAt ?: return null
        val zone = ZoneId.systemDefault()
        var z = Instant.ofEpochMilli(at).atZone(zone)
        val end = Instant.ofEpochMilli(now).atZone(zone)
        when (m.repeat) {
            Repeat.NONE -> return null
            Repeat.DAILY -> while (!z.isAfter(end)) z = z.plusDays(1)
            Repeat.WEEKLY -> while (!z.isAfter(end)) z = z.plusWeeks(1)
        }
        return z.toInstant().toEpochMilli()
    }

    private fun firePi(ctx: Context, id: String): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, id.hashCode(),
            Intent(ctx, ReminderReceiver::class.java).setAction(ACTION_FIRE)
                .setData(Uri.parse("yunxi://memo/$id")).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** 点通知 / 点状态栏闹钟图标 → 打开 App。带 `page=yunxi`，MainActivity 想直达云曦页就读它。 */
    private fun openPi(ctx: Context): PendingIntent =
        PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("page", "yunxi")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}

/** 到点响 + 开机 / 升级后重排。Manifest 里同时收 BOOT_COMPLETED 和 MY_PACKAGE_REPLACED。 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Reminders.rescheduleAll(ctx)
            Reminders.ACTION_FIRE -> intent.getStringExtra(Reminders.EXTRA_ID)?.let { Reminders.fire(ctx, it) }
        }
    }
}
