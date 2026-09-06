package app.yxi.ui.rhythm

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 老板挑定的 8 款碎裂特效，逐行从 `design/shatter/` 下的四个 js 搬过来（stylized / light / glass / physical 各取几款）。
 * （注释里别写 `路径/星号.js` 这种通配：Kotlin 块注释会嵌套，`/` 紧跟 `*` 就把后面整个文件吞进注释了。）
 * 每个数、每条曲线、`rng` 的调用次数和顺序都跟网页一致 —— 同 seed 同 t，App 和网页画出同一帧。
 * 网页里「先把 rng 全抽进数组再画」的那几款，这里改成在同一个循环开头按同样顺序抽（画的顺序没变，序列就没变）。
 */
object Shatters {
    /** 顺序 = 老板挑的顺序，名字跟网页画廊一致 */
    val all: List<Named<ShatterFx>> = listOf(
        Named("方粒四散", ShatterFx { tile, t, rng -> squares(tile, clamp01(t), rng) }),
        Named("切片错位", ShatterFx { tile, t, rng -> slices(tile, clamp01(t), rng) }),
        Named("冲击光尘", ShatterFx { tile, t, rng -> shockDust(tile, clamp01(t), rng) }),
        Named("扫描消散", ShatterFx { tile, t, rng -> scanline(tile, clamp01(t), rng) }),
        Named("溶解成光", ShatterFx { tile, t, rng -> dissolve(tile, clamp01(t), rng) }),
        Named("樱瓣飘落", ShatterFx { tile, t, rng -> petals(tile, clamp01(t), rng) }),
        Named("水晶棱柱", ShatterFx { tile, t, rng -> prism(tile, clamp01(t), rng) }),
        Named("压扁弹回", ShatterFx { tile, t, rng -> squash(tile, clamp01(t), rng) }),
    )

    private val TAU = PI.toFloat() * 2
    private val DEG = (180 / PI).toFloat()
    private val W = Color.White
    private val K = Color.Black

    // ── 各组 JS 头部的共享 helper ──
    private fun clamp01(v: Float) = if (v < 0f) 0f else if (v > 1f) 1f else v
    private fun outExpo(u: Float) = if (u <= 0f) 0f else if (u >= 1f) 1f else 1 - 2f.pow(-10 * u)
    private fun outQuad(u: Float) = 1 - (1 - u) * (1 - u)
    private fun outCubic(u: Float) = 1 - (1 - clamp01(u)).pow(3)
    private fun smooth(a: Float, b: Float, v: Float): Float { val u = clamp01((v - a) / (b - a)); return u * u * (3 - 2 * u) }
    /** 起手一闪：0 → 峰值(0.06) → 0.22 熄（光组） */
    private fun flash(t: Float) = if (t < 0.06f) t / 0.06f else clamp01(1 - (t - 0.06f) / 0.16f)

    /** 网页的 mix / shade / rgba(c,a,k)：按 RGB 通道直线插值（不用 Compose 的 lerp，那个走 Oklab，颜色会偏） */
    private fun Color.mix(to: Color, k: Float) = Color(ch(red, to.red, k), ch(green, to.green, k), ch(blue, to.blue, k))
    private fun ch(a: Float, b: Float, k: Float) = (a + (b - a) * k).let { if (it > 0f) min(it, 1f) else 0f }
    /** 透明度夹到 0..1（越界 Color() 会抛；NaN 当 0） */
    private fun Color.a(alpha: Float) = copy(alpha = if (alpha > 0f) min(alpha, 1f) else 0f)

    /** 三层描线仿霓虹辉光（光组的 glowStroke，这里只描圆） */
    private fun DrawScope.glowCircle(center: Offset, r: Float, c: Color, a: Float, lw: Float) {
        drawCircle(c.a(a * 0.28f), r, center, style = Stroke(lw * 4))
        drawCircle(c.mix(W, 0.35f).a(a * 0.7f), r, center, style = Stroke(lw * 1.4f))
        drawCircle(c.mix(W, 0.95f).a(a), r, center, style = Stroke(lw * 0.6f))
    }

    /** 樱花瓣：以原点为中心、尖端朝 -y、尖端带缺口 */
    private fun petal(p: Path, L: Float) {
        val wd = L * 0.62f
        p.rewind()
        p.moveTo(0f, -L * 0.7f)
        p.quadraticTo(wd * 0.45f, -L * 1.15f, wd, -L * 0.45f)
        p.quadraticTo(wd * 0.95f, L * 0.35f, 0f, L)
        p.quadraticTo(-wd * 0.95f, L * 0.35f, -wd, -L * 0.45f)
        p.quadraticTo(-wd * 0.45f, -L * 1.15f, 0f, -L * 0.7f)
        p.close()
    }

    /**
     * 有地面的刚体运动（全解析，物理组）：抛物线 → 落地弹一次（弹性 e，水平速度打 6 折）→ 再落地就躺平、指数刹车。
     * floor 是质心能到的最低 y；算完把 (x, y, 角度) 交给 draw。
     */
    private inline fun rigid(
        x0: Float, y0: Float, vx: Float, vy: Float, spin: Float, floor: Float, g: Float, e: Float, t: Float,
        draw: (x: Float, y: Float, ang: Float) -> Unit,
    ) {
        val t1 = (-vy + sqrt(max(0f, vy * vy - 2 * g * (y0 - floor)))) / g
        val vi = vy + g * t1
        if (t < t1) { draw(x0 + vx * t, y0 + vy * t + 0.5f * g * t * t, spin * t); return }
        val vb = -e * vi; val u = t - t1; val u2 = -2 * vb / g
        if (u < u2) { draw(x0 + vx * (t1 + 0.6f * u), floor + vb * u + 0.5f * g * u * u, spin * (t1 + 0.5f * u)); return }
        val s = t1 + 0.6f * u2 + 0.6f * (1 - exp(-10 * (u - u2))) / 10   // 第二次落地后滑行距离（等效时间）
        draw(x0 + vx * s, floor, spin * (s - 0.1f * u2))
    }

    // ── 方粒四散（stylized）：Phigros 式，小方块四散缩小，两层方框撑开淡出 ──
    private fun DrawScope.squares(tile: Tile, t: Float, rng: Rng) {
        val (x, y, w, h, c) = tile
        val cx = x + w / 2; val cy = y + h / 2; val m = min(w, h); val S = sqrt(w * h)
        val lit = c.mix(W, 0.5f)
        // 本体：白闪一下、微放大，0.04 内消失
        if (t < 0.04f) {
            val u = t / 0.04f; val s = 1 + 0.06f * u
            drawRect(c.mix(W, 0.6f).a(1 - u), Offset(cx - w * s / 2, cy - h * s / 2), Size(w * s, h * s))
        }
        // 两层方框撑开：外层随方块长宽比，内层正方
        val frame = Stroke(1.5f)
        for (k in 0..1) {
            val u = clamp01((t - k * 0.06f) / (1 - k * 0.06f)); val e = outExpo(u)
            val fw = if (k == 1) m * (0.4f + 0.45f * e) else w * (0.5f + 0.65f * e)
            val fh = if (k == 1) fw else h * (0.5f + 0.65f * e)
            drawRect((if (k == 1) c else lit).a(0.9f * (1 - smooth(0.05f, 0.6f, u))), Offset(cx - fw / 2, cy - fh / 2), Size(fw, fh), style = frame)
        }
        // 方粒：沿贴着方块的椭圆随机方向冲出、边转边缩
        val e = outExpo(t); val a = 1 - smooth(0.35f, 1f, t); val rx = w / 2 + m * 0.4f; val ry = h / 2 + m * 0.4f
        val p = Path()
        for (i in 0 until 32) {
            val ang = rng.next() * TAU; val d = (0.3f + 0.7f * rng.next()) * e; val s0 = S * (0.03f + 0.04f * rng.next())
            val rot = rng.next() * TAU + (rng.next() - 0.5f) * 6 * t; val tint = rng.next()
            val s = s0 * (1 - 0.75f * t) / 2
            val px = cx + cos(ang) * rx * d; val py = cy + sin(ang) * ry * d
            val co = cos(rot) * s; val si = sin(rot) * s
            p.rewind()
            p.moveTo(px + co - si, py + si + co)
            p.lineTo(px - co - si, py - si + co)
            p.lineTo(px - co + si, py - si - co)
            p.lineTo(px + co + si, py + si - co)
            p.close()
            drawPath(p, (if (tint < 0.3f) W else if (tint < 0.7f) lit else c).a(a))
        }
    }

    // ── 切片错位（stylized）：方块拆成横向细条，抖动错位后左右滑出 ──
    private fun DrawScope.slices(tile: Tile, t: Float, rng: Rng) {
        val (x, y, w, h, c) = tile
        val n = (h / 12).roundToInt().coerceIn(6, 16); val sh = h / n
        val dark = c.mix(K, 0.35f); val lit = c.mix(W, 0.55f)
        val frame = (t * 36).toInt(); val fade = 1 - smooth(0.6f, 1f, t)
        for (i in 0 until n) {
            val dir = if (rng.next() < 0.5f) -1 else 1; val delay = rng.next() * 0.25f; val dist = w * (0.35f + 0.55f * rng.next())
            val shade = rng.next(); val jit = (rng.next() * 97).toInt()
            val u = clamp01((t - delay) / 0.45f); val e = outCubic(u)
            // 滑出前：按帧量化的左右抖动
            val jitter = if (u > 0) 0f else ((frame * 7 + i * 13 + jit) % 5 - 2) * w * 0.02f
            val hh = sh * (1 - 0.5f * e); val oy = y + i * sh + (sh - hh) / 2
            val ox = x + jitter + dir * dist * e; val a = (1 - 0.85f * e) * fade
            // 残影：拖在身后，速度越快越长
            val trail = if (u > 0) dir * w * 0.12f * (1 - u) * (1 - u) else 0f
            if (trail != 0f) drawRect(lit.a(0.5f * a), Offset(ox - trail, oy), Size(w, hh))
            drawRect((if (shade < 0.25f) dark else c).a(a), Offset(ox, oy), Size(w, hh))
        }
        // 扫描线闪烁（0.2 内）
        if (t < 0.2f) {
            val col = W.a(0.6f * (1 - t / 0.2f))
            for (k in 0..1) {
                val row = (frame * 31 + k * 17) % n
                drawRect(col, Offset(x - w * 0.05f, y + row * sh), Size(w * 1.1f, 1f))
            }
        }
    }

    // ── 冲击光尘（light）：一圈冲击波从点击处荡开，波前扫过之处方块震成光尘四散 ──
    private fun DrawScope.shockDust(tile: Tile, t: Float, rng: Rng) {
        val (x, y, w, h, c) = tile; val s = min(w, h) / 100
        val cx = x + w * (0.35f + rng.next() * 0.3f); val cy = y + h * (0.35f + rng.next() * 0.3f)
        val R = hypot(max(cx - x, x + w - cx), max(cy - y, y + h - cy)) // 到最远角
        val f = flash(t); val pr = outQuad(clamp01(t / 0.7f)); val r = pr * R // 波进度
        // 震：起手抖一下（整个特效跟着抖，含光尘）
        val sh = f * 2.2f * s
        translate(sin(t * 190) * sh, cos(t * 140) * sh * 0.6f) {
            clipRect(x, y, x + w, y + h) {
                // 本体：波内已成尘，只剩波外
                if (pr < 1) {
                    val body = Path().apply {
                        fillType = PathFillType.EvenOdd
                        addRect(Rect(x, y, x + w, y + h)); addOval(Rect(cx - r, cy - r, cx + r, cy + r))
                    }
                    drawPath(body, c.mix(W, f * 0.7f))
                }
                // 波前：内侧拖一圈渐隐余晖 + 亮边
                val ra = sqrt(1 - pr)
                if (ra > 0.01f && r > 0.5f) {
                    val gw = min(r, (10 + f * 6) * s); val half = c.mix(W, 0.5f)
                    drawCircle(
                        Brush.radialGradient((r - gw) / r to half.a(0f), 1f to half.a(0.55f * ra), center = Offset(cx, cy), radius = r),
                        r, Offset(cx, cy),
                    )
                    glowCircle(Offset(cx, cy), r, c, ra, (2 + f * 2) * s)
                }
            }
            // 光尘：波扫到即离体，径向飞散
            for (i in 0 until 230) {
                val u = rng.next(); val v = rng.next(); val sp = rng.next(); val sz = rng.next(); val wh = rng.next()
                val px = x + u * w; val py = y + v * h
                val d = hypot(px - cx, py - cy).let { if (it == 0f) 1f else it }
                val tb = 0.7f * (1 - sqrt(1 - min(d / R, 0.999f))) // F(tb) = d/R
                if (t < tb) continue
                val age = (t - tb) / (1 - tb); val e = outCubic(age)
                val a = (1 - age).pow(1.5f)
                if (a < 0.02f) continue
                val dist = e * (0.08f + sp * 0.2f) * min(w, h) * (0.5f + 0.5f * d / R)
                val qx = px + (px - cx) / d * dist; val qy = py + (py - cy) / d * dist
                val r2 = (0.45f + sz * sz * 1.1f) * s * (1 - age * 0.5f)
                drawRect(c.mix(W, if (wh > 0.7f) 0.9f else 0.3f).a(a), Offset(qx - r2, qy - r2), Size(r2 * 2, r2 * 2), blendMode = BlendMode.Plus)
            }
        }
    }

    // ── 扫描消散（light）：一道扫描线自上而下掠过，扫过的行撕成光带向两侧散去 ──
    private fun DrawScope.scanline(tile: Tile, t: Float, rng: Rng) {
        val (x, y, w, h, c) = tile; val s = min(w, h) / 100
        // 行数用 double 算：h≥108 时 h/(h/36) 应恰好 36，float 会得 36.000001 → ceil 成 37，rng 序列就跟网页错开了
        val rowHd = max(3.0, h / 36.0); val rows = ceil(h / rowHd).toInt(); val rowH = rowHd.toFloat()
        val f = flash(t); val ly = y + outQuad(clamp01(t / 0.8f)) * h // 扫描线进度
        // 下方完整本体
        if (ly < y + h) drawRect(c.mix(W, f * 0.6f), Offset(x, ly), Size(w, y + h - ly))
        // 上方各行：被扫过后横向撕开、变淡
        for (j in 0 until rows) {
            val r1 = rng.next(); val r2 = rng.next(); val r3 = rng.next()
            val q = min((j + 0.5f) / rows, 0.999f)
            val tp = 0.8f * (1 - sqrt(1 - q)) // F(tp) = q
            if (t < tp) continue
            val age = clamp01((t - tp) / min(0.42f, 1 - tp))
            if (age >= 1) continue
            val e = outCubic(age); val ry = y + j * rowH; val rh = max(1f, rowH - 1)
            val split = w * (0.2f + r1 * 0.6f); val slide = e * w * (0.15f + r2 * 0.25f); val k = 1 - e * (0.5f + r3 * 0.3f)
            val col = c.mix(W, 0.35f + (1 - age) * 0.5f).a((1 - age).pow(1.6f))
            val lw = split * k; val rw = (w - split) * k // 两半各自缩短，裂口从撕开点张大
            drawRect(col, Offset(x + split - lw - slide, ry), Size(lw, rh))
            drawRect(col, Offset(x + split + slide, ry), Size(rw, rh))
        }
        // 扫描线 + 上沿光晕
        val la = 1 - t * t * t
        val gh = min(16f, min(h * 0.25f, ly - y)); val hot = c.mix(W, 0.8f)
        if (gh > 0) drawRect(Brush.verticalGradient(listOf(hot.a(0f), hot.a(0.55f * la)), startY = ly - gh, endY = ly), Offset(x, ly - gh), Size(w, gh))
        drawRect(W.a(la), Offset(x, ly - 1), Size(w, 2f))
        // 扫过时溅起的小光粒
        for (i in 0 until 70) {
            val u = rng.next(); val v = rng.next(); val sp = rng.next(); val sz = rng.next()
            val tb = u * 0.75f
            if (t < tb) continue
            val age = (t - tb) / (1 - tb); val e = outCubic(age)
            val a = (1 - age) * (1 - age)
            if (a < 0.02f) continue
            val px = x + v * w; val py = y + outQuad(clamp01(tb / 0.8f)) * h - (0.1f + sp * 0.3f) * h * e
            val r = (0.4f + sz * sz * 0.9f) * s
            drawRect(c.mix(W, 0.7f).a(a), Offset(px - r, py - r), Size(r * 2, r * 2), blendMode = BlendMode.Plus)
        }
    }

    // ── 溶解成光（light）：溶解前沿自上而下扫过，方块化作光点缓缓上浮 ──
    private fun DrawScope.dissolve(tile: Tile, t: Float, rng: Rng) {
        val (x, y, w, h, c) = tile; val s = min(w, h) / 100
        val f = flash(t); val top = y + outQuad(clamp01(t / 0.85f)) * h // 前沿走过的高度比例
        if (top < y + h) {
            drawRect(c.mix(W, f * 0.6f).a(1 - t * t * 0.4f), Offset(x, top), Size(w, y + h - top))
            // 前沿亮边
            val gh = min(22f, min(h * 0.3f, y + h - top)); val hot = c.mix(W, 0.95f)
            drawRect(Brush.verticalGradient(listOf(hot.a(0.9f * (1 - t)), hot.a(0f)), startY = top, endY = top + gh), Offset(x, top), Size(w, gh))
        }
        for (i in 0 until 180) {
            val u = rng.next(); val v = rng.next(); val sp = rng.next(); val sz = rng.next(); val wh = rng.next(); val dr = rng.next()
            val tb = u * 0.8f // 出生 = 前沿经过之时
            if (t < tb) continue
            val age = (t - tb) / (1 - tb)
            val e = outCubic(age)
            val px = x + v * w + (dr - 0.5f) * 14 * s * e
            val py = y + outQuad(clamp01(tb / 0.85f)) * h - (0.25f + sp * 0.75f) * h * 0.45f * e
            val a = (1 - age).pow(1.5f)
            if (a < 0.02f) continue
            // 初生白热，渐冷回本色
            drawCircle(c.mix(W, 0.15f + (0.3f + wh * 0.6f) * (1 - age)).a(a), (0.5f + sz * sz * 1.6f) * s, Offset(px, py), blendMode = BlendMode.Plus)
        }
    }

    // ── 樱瓣飘落（stylized）：方块散成花瓣，旋着飘开、随风落下 ──
    private fun DrawScope.petals(tile: Tile, t: Float, rng: Rng) {
        val (x, y, w, h, c) = tile
        val cx = x + w / 2; val cy = y + h / 2; val L = sqrt(w * h) * 0.1f; val drop = max(h, w * 0.6f)
        // 本体：本色淡出（0.12 内）
        if (t < 0.12f) drawRect(c.a((1 - t / 0.12f).pow(1.3f)), Offset(x, y), Size(w, h))
        val p = Path()
        for (i in 0 until 30) {
            val px0 = x + w * rng.next(); val py0 = y + h * rng.next()
            val ang = atan2(py0 - cy, (px0 - cx) * 1.6f) + (rng.next() - 0.5f)
            val burst = w * (0.03f + 0.32f * rng.next()); val spin = (rng.next() - 0.5f) * 9; val rot0 = rng.next() * TAU
            val sway = rng.next() * TAU; val fall = drop * (0.45f + 0.55f * rng.next()); val tint = rng.next(); val delay = rng.next() * 0.08f
            val tt = clamp01((t - delay) / (1 - delay)); val e = outExpo(tt)
            val px = px0 + cos(ang) * burst * e + sin(tt * 4 + sway) * w * 0.06f * tt
            val py = py0 + sin(ang) * burst * e * 0.5f + fall * tt * tt
            val col = c.mix(W, 0.1f + 0.55f * tint).a(smooth(0f, 0.05f, tt) * (1 - smooth(0.5f, 1f, tt)))
            petal(p, L * (0.7f + 0.6f * tint) * (1 - 0.3f * tt))
            withTransform({ translate(px, py); rotate((rot0 + spin * tt) * DEG, Offset.Zero) }) { drawPath(p, col) }
        }
    }

    // ── 水晶棱柱（glass）：闪白后收缩消散，细长棱柱四散，棱面一亮一暗，伴随星点 ──
    private fun DrawScope.prism(tile: Tile, t: Float, rng: Rng) {
        val (x, y, w, h, base) = tile
        val cx = x + w / 2; val cy = y + h / 2; val M = max(w, h); val m = min(w, h)
        val n = (w * h / 500).roundToInt().coerceIn(24, 60)
        val tk = 0.3f
        if (t < tk) { // 本体：闪白 → 缩小消散
            val k = t / tk; val s = 1 - 0.2f * k * k
            scale(s, s, Offset(cx, cy)) {
                drawRect(base.mix(W, max(0f, 1 - t / 0.08f) * 0.8f).a((1 - k).pow(1.2f)), Offset(x, y), Size(w, h))
            }
        }
        val G = 0.35f * h
        val p = Path()
        for (i in 0 until n) {
            val ox = cx + (rng.next() - 0.5f) * 0.8f * w; val oy = cy + (rng.next() - 0.5f) * 0.8f * h
            val ang0 = atan2(oy - cy, ox - cx) + (rng.next() - 0.5f) * 0.8f
            val L = max(6f, (0.1f + 0.2f * rng.next()) * m)
            val ux = cos(ang0); val uy = sin(ang0); val v = (0.35f + 0.65f * rng.next()) * 0.5f * M
            val Wd = L * (0.22f + 0.16f * rng.next()); val dl = 0.08f * rng.next(); val spin = (rng.next() - 0.5f) * 5
            val a0 = ang0 + (rng.next() - 0.5f) * 0.6f; val ph = rng.next() * TAU
            val tau = clamp01((t - dl) / (1 - dl))
            if (tau == 0f) continue
            val a = (1 - tau).pow(1.3f); val f = outCubic(tau) * v; val ang = a0 + spin * tau; val sc = 1 - 0.3f * tau
            val e = L * 0.22f; val g = max(0f, sin(ang * 2 + ph)).pow(6)
            withTransform({ translate(ox + ux * f, oy + uy * f + G * tau * tau); rotate(ang * DEG, Offset.Zero); scale(sc, sc, Offset.Zero) }) {
                p.rewind(); p.moveTo(-L / 2, 0f); p.lineTo(-L / 2 + e, -Wd / 2); p.lineTo(L / 2 - e, -Wd / 2); p.lineTo(L / 2, 0f); p.close()
                drawPath(p, base.mix(W, 0.45f + 0.5f * g).a(a))
                p.rewind(); p.moveTo(-L / 2, 0f); p.lineTo(L / 2, 0f); p.lineTo(L / 2 - e, Wd / 2); p.lineTo(-L / 2 + e, Wd / 2); p.close()
                drawPath(p, base.mix(K, 0.35f).a(a))
                drawLine(W.a(0.7f * a), Offset(-L / 2, 0f), Offset(L / 2, 0f), strokeWidth = 0.8f)
            }
        }
        for (i in 0 until 36) {
            val sx = cx + (rng.next() - 0.5f) * 1.15f * w; val sy = cy + (rng.next() - 0.5f) * 1.15f * h
            val ts = 0.05f + 0.5f * rng.next(); val ss = (1.5f + 2.5f * rng.next()) * M / 160
            val k = clamp01((t - ts) / 0.22f)
            if (k == 0f || k == 1f) continue
            val a = sin(PI.toFloat() * k); val s = ss * (0.6f + 0.4f * a); val q = s * 0.28f
            p.rewind()
            p.moveTo(sx, sy - s); p.lineTo(sx + q, sy - q); p.lineTo(sx + s, sy); p.lineTo(sx + q, sy + q)
            p.lineTo(sx, sy + s); p.lineTo(sx - q, sy + q); p.lineTo(sx - s, sy); p.lineTo(sx - q, sy - q); p.close()
            drawPath(p, W.a(0.9f * a))
        }
    }

    // ── 压扁弹回（physical）：先被压扁、再弹起拉长，绷不住散成一把细碎屑落地 ──
    private fun DrawScope.squash(tile: Tile, t: Float, rng: Rng) {
        val (x, y, w, h, color) = tile
        val cx = x + w / 2; val floor = y + h; val tb = 0.28f // tb：崩散时刻
        val light = color.mix(W, 0.4f)
        // 碎屑（先画，主体盖在上面）：24 块小渣 + 140 粒细屑，从崩散瞬间的主体里飞出，向两侧撑开 + 向上抛，带重力落地弹一下
        val g = 12 * h; val cap = 1 - smooth(0.5f, 1f, t); val unit = w / 120
        for (i in 0 until 164) {
            val lx = (rng.next() - 0.5f) * w * 0.8f; val ly = -rng.next() * h * 1.25f
            val size = (if (i < 24) 4 + 5 * rng.next() else 1.5f + 2.5f * rng.next()) * unit
            val vx = lx / w * 1.6f * w * (0.6f + 0.8f * rng.next()); val vy = -(0.4f + 1.4f * rng.next()) * h; val spin = (rng.next() - 0.5f) * 12
            val life = 0.35f + 0.35f * rng.next(); val pale = rng.next() < 0.3f
            val tau = t - tb
            if (tau <= 0) continue
            val a = min(cap, 1 - tau / life)
            if (a <= 0) continue
            rigid(cx + lx, floor + ly, vx, vy, spin, floor - size / 2, g, 0.3f, tau) { px, py, ang ->
                rotate(ang * DEG, Offset(px, py)) {
                    drawRect((if (pale) light else color).a(a), Offset(px - size / 2, py - size / 2), Size(size, size))
                }
            }
        }
        // 主体（画在碎屑上面，散掉前盖住碎屑）：底边固定，竖向 1 → 0.55（压扁）→ 1.3（弹起拉长）→ 崩散后继续胀大并消失
        val sy = if (t < 0.14f) 1 - 0.45f * smooth(0f, 0.14f, t) else if (t < tb) 0.55f + 0.75f * smooth(0.14f, tb, t) else 1.3f + (t - tb) * 1.5f
        val sx = 1 + (1 - sy) * 0.7f
        val bodyA = 1 - smooth(tb + 0.03f, tb + 0.11f, t)
        if (bodyA > 0) drawRect(color.a(bodyA), Offset(cx - w * sx / 2, floor - h * sy), Size(w * sx, h * sy))
        // 触地那一下：底边一道白光，压得越狠越宽
        val ga = 0.5f * smooth(0f, 0.04f, t) * (1 - smooth(0.06f, 0.3f, t))
        if (ga > 0) drawRect(W.a(ga), Offset(cx - w * sx * 0.6f, floor - 0.75f), Size(w * sx * 1.2f, 1.5f))
    }
}
