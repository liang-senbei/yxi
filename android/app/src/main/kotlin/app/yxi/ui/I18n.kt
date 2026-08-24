package app.yxi.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 界面语言。**中文原文就是 key。**
 *
 * 用法：把界面上的字面量套一层 [t] —— `Text("设置")` → `Text(t("设置"))`。
 *
 * ⚠️ **为什么不用 Android 的 `strings.xml`。** 认真比过，这个项目里它更差：
 *
 *   1. **一多半的串带插值**（`"$n 台"`、`"连不上 ${host.display}"`）。
 *      资源那套要写成 `%1$s` 占位再 `getString(id, a, b)` ——
 *      参数顺序错了不报错，只是显示错，而中文里参数顺序常常跟英文不一样。
 *      套在 Kotlin 里就还是普通字符串模板，编译器管着。
 *   2. **一堆要翻的字在非 composable 里**（[app.yxi.ssh.SshSession] 的报错、
 *      [app.yxi.watch.EventService] 的通知文案、[app.yxi.agent.Transcript] 的卡片标题）。
 *      资源那套要 `Context`，就得把 Context 一路传进这些纯逻辑类里 —— 为了翻译污染分层。
 *   3. **起 id 是纯开销**。`R.string.hosts_empty_hint` 这种名字要发明、要记、要在两处对齐，
 *      而中文原文本身就是天然的、唯一的、看一眼就知道是哪句的 key。
 *
 * **代价（写清楚，别以后当成 bug）**：
 *   · 没有编译期检查「这句翻了没有」。漏翻的表现是**那句仍然显示中文**，不会崩。
 *     `dev/i18n-check.sh` 扫源码比对 [En] 补漏。
 *   · 中文原文改了，翻译就对不上、自动回落成中文。所以改文案时要顺手改 [En] 的 key。
 *   · 不接系统的「按应用设置语言」。这个 app 的语言是自己的开关，不跟系统走。
 *
 * ⚠️ **[lang] 是 Compose 的 state** —— 界面上读了它的地方会在切换时自动重组，
 * 所以切语言是**立刻生效**的，不用重启 Activity。
 */
object I18n {

    enum class Lang(val tag: String, val label: String) {
        ZH("zh", "简体中文"),
        EN("en", "English"),
    }

    var lang by mutableStateOf(Lang.ZH)
        private set

    private const val KEY = "lang"
    private fun p(ctx: Context) = ctx.getSharedPreferences("yxi", Context.MODE_PRIVATE)

    /**
     * 从盘上读回上次的选择。
     *
     * ⚠️ **前台服务也要调**：通知文案走的是同一个 [t]，而服务可能比界面先起来
     * （开机自启、被系统拉起）。只在 Activity 里读的话，
     * 通知会**先弹几条中文**再跟上 —— 这种不一致比全中文更奇怪。
     */
    fun load(ctx: Context) {
        val tag = p(ctx).getString(KEY, null) ?: return
        lang = Lang.entries.firstOrNull { it.tag == tag } ?: return
    }

    fun set(ctx: Context, l: Lang) {
        lang = l
        p(ctx).edit().putString(KEY, l.tag).apply()
    }
}

/**
 * 翻译一句。[zh] 是中文原文，同时也是查表的 key。
 *
 * ⚠️ **查不到就原样返回中文，绝不返回空串或 key 名。** 漏翻的界面应该是
 * 「这句还是中文」，而不是「这里空了一块」或者冒出个 `hosts_empty_hint`。
 */
fun t(zh: String): String =
    if (I18n.lang == I18n.Lang.ZH) zh else En.map[zh] ?: zh
