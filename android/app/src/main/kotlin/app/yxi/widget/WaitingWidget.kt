package app.yxi.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import app.yxi.MainActivity
import app.yxi.R
import app.yxi.ui.I18n
import app.yxi.ui.t

/**
 * 桌面小组件：一眼看会话状态（几个等你 / 几个在跑），点一下进 app。
 * 没有连接，读 [app.yxi.watch.EventService] 写进 prefs 的「上一次已知态」；服务每次变更调 [refresh] 推一版。
 */
class WaitingWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        I18n.load(ctx.applicationContext)
        val p = ctx.getSharedPreferences("yxi", Context.MODE_PRIVATE)
        val n = p.getInt("waitingCount", 0)
        val w = p.getInt("workingCount", 0)
        val names = p.getString("waitingNames", "").orEmpty()
        val v = RemoteViews(ctx.packageName, R.layout.widget_waiting)
        v.setTextViewText(R.id.w_title,
            if (n > 0) t("%d 个会话等你").format(n)
            else if (w > 0) t("%d 个会话在跑").format(w) else "Claude")
        v.setTextViewText(R.id.w_sub, if (n > 0 && names.isNotBlank()) names else if (w > 0) t("Claude 正在干活") else t("盯着呢"))
        val pi = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        v.setOnClickPendingIntent(R.id.w_root, pi)
        ids.forEach { mgr.updateAppWidget(it, v) }
    }

    companion object {
        /** 状态变了就推一版（服务在 refreshOngoing 里调）。没有摆出小组件就是 no-op。 */
        fun refresh(ctx: Context) = runCatching {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, WaitingWidget::class.java))
            if (ids.isNotEmpty()) WaitingWidget().onUpdate(ctx, mgr, ids)
        }.let { }
    }
}
