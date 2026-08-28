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
