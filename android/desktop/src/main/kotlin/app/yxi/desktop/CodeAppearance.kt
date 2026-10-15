package app.yxi.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Code 风格的两项外观设置（M4q，规格表 §3.7、PRD §6「对话宽度 / 字体等外观设置」）：对话字号、对话宽度。
 * 只在 Code 风格下读（经典没有这两项，也不受影响）；值存 Store.pref，改了马上重组。
 * 参考端只量过默认档（字号「中」= 14 / 20、宽度「窄」= 768，规格表 §0.1）；其余档是 Yxi 自定的，标「待量」，都集中在这两张表里。
 */

/**
 * 对话字号三档。[size] 是对话正文的字号（sp）；整列文字按 [scale] = size / 14 一起缩放（行高、代码、工具行、时间都跟着走，比例不变），
 * 所以「中」的行高还是 20，「小」≈ 13 / 18.6、「大」≈ 16 / 22.9。小、大两档待量（规格表 §6）。
 */
internal enum class ChatFontSize(val key: String, val label: String, val size: Float) {
    Small("small", "小", 13f), Medium("medium", "中", 14f), Large("large", "大", 16f);

    /** 对「中」的倍数：「中」正好是 1（14f / 14f），乘进 fontScale 后和不设一样，默认档逐像素不变。 */
    val scale: Float get() = size / Medium.size
    /** 正文行高（sp）：20 × [scale]。 */
    val lineHeight: Float get() = 20f * scale

    companion object {
        const val PREF = "ui.chatFontSize"
        /** 认不出的值（手改坏的、以后的新档）一律按「中」。 */
        fun of(key: String?): ChatFontSize = entries.firstOrNull { it.key == key } ?: Medium
        val current: ChatFontSize @Composable get() = of(Store.pref(PREF, Medium.key))
    }
}

/**
 * 对话宽度三档：[column] 同时管对话列和停靠区（审批卡、排队、输入卡和页脚）以及新会话页的输入区（规格表 §2「同时约束对话列和输入卡」）；
 * 主区放不下时照旧两侧各留 40（宽 = min(column, 主区 − 80)）。[bubbleMax] 是用户消息气泡的最宽：「窄」是实测的 627.2，其余按同一比例（≈81.7%）。
 * 中、宽两档待量（规格表 §6），取 896 / 1024（每档 +128）。
 */
internal enum class ChatWidth(val key: String, val label: String, val column: Dp, val bubbleMax: Dp) {
    Narrow("narrow", "窄", 768.dp, 627.2.dp), Medium("medium", "中", 896.dp, 731.7.dp), Wide("wide", "宽", 1024.dp, 836.3.dp);

    companion object {
        const val PREF = "ui.chatWidth"
        /** 认不出的值一律按「窄」（参考端默认、Yxi 原来写死的 768）。 */
        fun of(key: String?): ChatWidth = entries.firstOrNull { it.key == key } ?: Narrow
        val current: ChatWidth @Composable get() = of(Store.pref(PREF, Narrow.key))
    }
}

/**
 * 按对话字号缩放 [content] 里的文字：把 [ChatFontSize.scale] 乘进 LocalDensity 的 fontScale（dp 不变，图标、留白、框的尺寸都不动）。
 * 「中」时乘 1，提供的 Density 和外层相等，画出来逐像素不变。一直提供（不按档分支），切档时行里的 remember 状态不丢。
 * 只包对话内容（[CodeColumn] 和排队的气泡），输入卡、审批卡、侧栏不跟。
 */
@Composable
internal fun CodeChatText(content: @Composable () -> Unit) {
    val d = LocalDensity.current
    val scale = ChatFontSize.current.scale
    CompositionLocalProvider(LocalDensity provides Density(d.density, d.fontScale * scale), content = content)
}
