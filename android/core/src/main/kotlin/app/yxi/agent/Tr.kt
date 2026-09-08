package app.yxi.agent

/**
 * 文案翻译的缝：core 里的 `t("中文")` 走这里；Android 在启动时接成 ui/I18n 的 t()（跟着语言设置重组），桌面接自己的表。
 */
object Tr {
    @Volatile var fn: (String) -> String = { it }
    fun t(zh: String): String = fn(zh)
}
