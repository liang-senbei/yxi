package app.yxi.agent

/**
 * 平台缝：core 里不许 import android.*。日志由宿主接（Android 接 android.util.Log，桌面接 println）。
 */
object Plat {
    /** debug 包才打 jsch 的详细日志（Android 接 BuildConfig.DEBUG；桌面默认 false） */
    @Volatile var debug: Boolean = false
    @Volatile var log: (level: Char, tag: String, msg: String) -> Unit = { l, tag, msg -> println("$l/$tag: $msg") }
    fun logi(tag: String, msg: String) = log('I', tag, msg)
    fun logw(tag: String, msg: String) = log('W', tag, msg)
}
