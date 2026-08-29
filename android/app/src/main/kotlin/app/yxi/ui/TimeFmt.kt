package app.yxi.ui

/**
 * 相对时间：unix 秒 → 「刚刚 / 5分钟前 / 3小时前 / 2天前」。
 * 看板/悬浮卡瞄一眼「多久没动了」用。0 或未来时刻 → 空串（不显示）。
 */
fun ago(epochSec: Long): String {
    if (epochSec <= 0L) return ""
    val d = System.currentTimeMillis() / 1000 - epochSec
    return when {
        d < 0 -> ""
        d < 60 -> t("刚刚")
        d < 3600 -> t("%d 分钟前").format(d / 60)
        d < 86400 -> t("%d 小时前").format(d / 3600)
        else -> t("%d 天前").format(d / 86400)
    }
}

/**
 * 「等了多久」—— 跟 [ago] 同一批阈值，但**不带「前」字**。
 *
 * ⚠️ 不要拿 `ago(x).removeSuffix("前")` 凑：那在英文下（`3 minutes ago`）
 * 剪不掉，会变成「已经等了 3 minutes ago」。分成两个函数，各自的译文各自完整。
 */
fun waited(epochSec: Long): String {
    if (epochSec <= 0L) return ""
    val d = System.currentTimeMillis() / 1000 - epochSec
    return when {
        d < 60 -> ""                                   // 刚开始等，不值得说
        d < 3600 -> t("%d 分钟").format(d / 60)
        d < 86400 -> t("%d 小时").format(d / 3600)
        else -> t("%d 天").format(d / 86400)
    }
}

