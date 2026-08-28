package app.yxi.widget

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.yxi.MainActivity
import app.yxi.R
import app.yxi.ui.I18n
import app.yxi.ui.t

/**
 * 快捷设置磁贴：「Claude · N 个等你」—— 下拉一滑就看见，点一下进 app。
 * 没有连接，读 [app.yxi.watch.EventService] 写进 prefs 的「上一次已知态」。
 */
class WaitingTile : TileService() {
    override fun onStartListening() {
        I18n.load(applicationContext)
        val p = getSharedPreferences("yxi", MODE_PRIVATE)
        val n = p.getInt("waitingCount", 0)
        val w = p.getInt("workingCount", 0)
        qsTile?.apply {
            icon = Icon.createWithResource(this@WaitingTile, R.drawable.ic_stat_yxi)
            state = if (n > 0) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = if (n > 0) t("%d 个等你").format(n) else "Claude"
            if (Build.VERSION.SDK_INT >= 29) subtitle =
                if (n > 0) t("点开去处理") else if (w > 0) t("%d 在跑").format(w) else t("盯着呢")
            updateTile()
        }
    }

    override fun onClick() {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION") startActivityAndCollapse(i)
        }
    }
}
