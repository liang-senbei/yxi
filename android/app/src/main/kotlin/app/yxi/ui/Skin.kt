package app.yxi.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.yxi.ui.theme.LightPalette
import app.yxi.ui.theme.Palette
import app.yxi.ui.theme.YxiPalette

/**
 * 界面风格。跟 [I18n] 一个套路：状态是 Compose 的，改了立刻生效，选择落盘。
 */
object Skin {

    enum class Style(val key: String, private val zh: String, val palette: Palette) {
        // ⚠️ **名字不提别家产品。** 这是 Yxi 自己的浅色，不该拿别家产品的名字来叫 ——
        // 拿别人的产品名当自己界面选项的名字，既不准确也不体面。
        // ⚠️ `key` 保持原样不动（那两个字符串是**存在用户手机里的值**），改了等于
        // 把所有已经选过的人重置一遍。名字给人看，key 是数据，两者不该绑在一起。
        Light("gemini", "浅色", LightPalette),
        Dark("yxi", "深色", YxiPalette);

        // ⚠️ **`get()` 不是构造参数。** enum 常量的参数在**类初始化时求值一次**，
        // 之后换语言它不会跟着变 —— 现象是这一栏永远停在启动时那种语言，
        // 而同一屏别的字都变了。`SessionState.label` 踩过同一个坑，写法保持一致。
        val label: String get() = t(zh)
    }

    /**
     * ⚠️ **默认浅色**（用户定的：「以后默认就是浅色，新用户刚进来看见的就是浅色」）。
     * ⚠️ 只影响**没选过**的人 —— [load] 读到存过的值就照存的来，
     * 换默认值不该把老用户已经选好的东西掀掉。
     */
    var style by mutableStateOf(Style.Light)
        private set

    private fun p(ctx: Context) = ctx.getSharedPreferences("yxi", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        val k = p(ctx).getString("skin", null) ?: return
        style = Style.entries.firstOrNull { it.key == k } ?: return
    }

    fun set(ctx: Context, s: Style) {
        style = s
        p(ctx).edit().putString("skin", s.key).apply()
    }
}
