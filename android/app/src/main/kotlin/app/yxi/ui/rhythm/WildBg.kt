package app.yxi.ui.rhythm

import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * 「癫狂」难度的背景总入口（老板 2026-09-07 晚：「才四五种，要做 20 种，每个关卡随机三四个，做精致一点」）。
 * 20 种分四个文件（四个子代理各做五种，各自编译通过）：
 *   A 1~5   黑白网点 / 双层彩虹射线轮 / 螺旋点阵尾迹 / 纯色硬切 + 横带 / 棋盘格透视滚动
 *   B 6~10  隧道 / 星海跃迁 / 边缘均衡器 / 六角蜂巢脉冲 / 波浪条纹
 *   C 11~15 电路板 / 万花筒 / 极光 / 字符雨 / 复古地平线网格
 *   D 16~20 樱花飘落 / 扫描线 + 故障 / 泡泡上升 / 低多边形碎片 / 涡旋
 * 谱面 `stage` 关键帧里 `bg` 切到几号就画几号；0 = 平常的底光（StageGlow），这里只管 1 起；不认识的号画 1。
 * 约束（每个文件都守）：只用 DrawScope 图元、每帧 ≤600 次绘制、全按时间不按帧、任何自闪 ≤2.5Hz、calm 时 ≤0.5Hz 且不抖。
 */
object WildBg {
    /** @param style 1..20；[t] 秒；[beat] 拍相位 0..1；[energy] 0..1；[calm] 用户关了闪屏 / 减弱动效 */
    fun DrawScope.drawWildBg(style: Int, t: Float, beat: Float, energy: Float, calm: Boolean = false) {
        when (style) {
            in 1..5 -> with(WildBgA) { draw(style, t, beat, energy, calm) }
            in 6..10 -> with(WildBgB) { draw(style, t, beat, energy, calm) }
            in 11..15 -> with(WildBgC) { draw(style, t, beat, energy, calm) }
            in 16..20 -> with(WildBgD) { draw(style, t, beat, energy, calm) }
            else -> with(WildBgA) { draw(1, t, beat, energy, calm) }
        }
    }
}
