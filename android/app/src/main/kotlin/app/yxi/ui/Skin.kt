package app.yxi.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.yxi.ui.theme.GeminiPalette
import app.yxi.ui.theme.Palette
import app.yxi.ui.theme.YxiPalette

/**
 * 界面风格。跟 [I18n] 一个套路：状态是 Compose 的，改了立刻生效，选择落盘。
 */
object Skin {

    enum class Style(val key: String, val label: String, val palette: Palette) {
        Yxi("yxi", "Yxi 暖色深底", YxiPalette),
        Gemini("gemini", "Gemini 浅色", GeminiPalette),
    }

    var style by mutableStateOf(Style.Yxi)
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
