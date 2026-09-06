package app.yxi.ui.rhythm

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * 音游特效的契约 —— 跟网页试验台（`design/rhythm-bench.src.html`，tag `bench-final-20260906`）**一字不差**。
 * 老板在网页上挑定的那些特效（`design/hit/*.js`、`design/shatter/*.js`、`design/swipe/*.js`）
 * 按这个契约逐个移植成 Kotlin；改动效先改网页给老板看，定了再搬过来。
 *
 * 三条硬规矩（试验台里就是这么约束的）：
 *  · **无状态**：每帧从 `t` 和 `rng` 重新算，宿主可能同时播多个、也可能从任意 t 直接画一帧。
 *  · **确定性随机**：[Rng] 每帧按同一 seed 重开，所以每帧 `next()` 拿到同一序列 —— 粒子的初速 / 方向 / 大小从它派生。
 *  · **t=1 几乎全透明**。
 *
 * ⚠️ 舞台是固定深色，这里**只用传进来的颜色和白 / 黑 / 透明**，别碰主题色 getter（TROUBLESHOOTING #14）。
 */

/** mulberry32，跟试验台的 `rngOf(seed)` 同一个算法：同 seed 同序列，网页和 App 出一样的粒子。 */
class Rng(seed: Float) {
    private var a: Int = (seed.toDouble() * 4294967296.0).toLong().toInt()

    fun next(): Float {
        a += 0x6D2B79F5
        var t = a
        t = (t xor (t ushr 15)) * (1 or t)
        t = t + ((t xor (t ushr 7)) * (61 or t)) xor t
        return ((t xor (t ushr 14)).toLong() and 0xFFFFFFFFL).toFloat() / 4294967296f
    }
}

/**
 * 点击特效的入参。宿主调用前已把原点**平移到命中点**（判定线上那一点），x 轴沿判定线、y 轴向上为负。
 * @param u 基本尺度（≈ 屏高 3%，手机上 15~25px）；撑满时直径约 4~5u
 * @param noteH 音符薄片高度（8~11px）
 */
data class Hit(val u: Float, val color: Color, val perfect: Boolean, val noteH: Float)

/** 碎裂的入参：音符本体的矩形（左上角 + 宽高）。从 t=0 起本体归特效画，碎了就别再画整块。 */
data class Tile(val x: Float, val y: Float, val w: Float, val h: Float, val color: Color, val seed: Float)

/**
 * swipe 音符的入参。`x,y` 是音符**中心**；`dir` -1 左 / +1 右；`lineY` 判定线 y（音符往它落）；
 * `laneX/laneW` 所在轨道的横向范围。特效要画**完整的音符**（本体 + 方向标记）。
 */
data class SwipeNote(
    val x: Float, val y: Float, val w: Float, val h: Float, val color: Color,
    val dir: Int, val lineY: Float, val laneX: Float, val laneW: Float,
)

/** 点击特效：t 0..1（宿主按 0.5~0.6 秒播完） */
fun interface HitFx { fun DrawScope.draw(hit: Hit, t: Float, rng: Rng) }

/** 碎裂：t 0..1（宿主按 0.6 秒播完） */
fun interface ShatterFx { fun DrawScope.draw(tile: Tile, t: Float, rng: Rng) }

/** swipe 方向标记：phase 0..1 循环（0.8 秒一圈），给标记自己的动态用；静止帧也必须分得出左右 */
fun interface SwipeMark { fun DrawScope.draw(note: SwipeNote, phase: Float, rng: Rng) }

/** 名字 + 实现，名字跟网页画廊里的一致（老板是按名字挑的） */
data class Named<T>(val name: String, val fx: T)
