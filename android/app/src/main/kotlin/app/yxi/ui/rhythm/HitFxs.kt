package app.yxi.ui.rhythm

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 老板挑定的 9 款点击特效，从 `design/hit/` 下的五个 js 逐行搬过来：每个数、每条曲线、`rng` 的调用次数和顺序都跟网页一致
 * （同 seed 同一组粒子）。契约见 [HitFx]：原点已在命中点，t 0..1，[Rng] 每帧同 seed 重开。
 *
 * 网页里 `hit.judge` 只有 perfect / good 两种，所以 `judge === 'good'` 就是 `!hit.perfect`。
 * ⚠️ 线宽 / 小点半径的字面量（1.3、1.6、1.8、2…）在网页里是 CSS px（画布 `setTransform(DPR)`），
 * 宿主若按物理像素画，要自己乘密度；网页宿主还在外面套了 `globalAlpha = hitEnvelope(k)` 和每款的 `HIT_SCALE`，那是宿主的事。
 */
object HitFxs {
    /** 顺序 = 老板挑的顺序，名字跟网页画廊一致 */
    val all: List<Named<HitFx>> = listOf(
        Named("快门", HitFx { hit, t, rng -> shutter(hit, t, rng) }),
        Named("六边框", HitFx { hit, t, rng -> hexFrame(hit, t, rng) }),
        Named("三角翻转", HitFx { hit, t, rng -> triFlip(hit, t, rng) }),
        Named("均衡器", HitFx { hit, t, rng -> equalizer(hit, t, rng) }),
        Named("方粒", HitFx { hit, t, rng -> squareBits(hit, t, rng) }),
        Named("火花", HitFx { hit, t, rng -> sparks(hit, t, rng) }),
        Named("螺旋尘", HitFx { hit, t, rng -> spiralDust(hit, t, rng) }),
        Named("聚爆", HitFx { hit, t, rng -> implode(hit, t, rng) }),
        Named("碎环", HitFx { hit, t, rng -> shatterRing(hit, t, rng) }),
    )

    private const val TAU = 6.2831855f
    private const val PI_F = 3.1415927f
    private const val HALF_PI = 1.5707964f
    private const val RAD = 57.29578f   // 弧度 → 度（Compose 的 rotate / drawArc 用度）

    // ───────── 各 js 模块头部的共享 helper；重名的（snap / fade）按模块后缀区分 ─────────

    private fun clamp01(v: Float) = if (v < 0f) 0f else if (v > 1f) 1f else v

    /** JS 各模块的 rgba(c, a, k) / shade / tint：k>0 向白混，k<0 向黑混；alpha 截到 0..1（Color() 越界会抛） */
    private fun mix(c: Color, a: Float, k: Float = 0f): Color {
        val r: Float; val g: Float; val b: Float
        if (k > 0f) { r = c.red + (1f - c.red) * k; g = c.green + (1f - c.green) * k; b = c.blue + (1f - c.blue) * k }
        else { r = c.red * (1f + k); g = c.green * (1f + k); b = c.blue * (1f + k) }
        return Color(clamp01(r), clamp01(g), clamp01(b), clamp01(a))
    }
    private fun white(a: Float) = Color.White.copy(alpha = clamp01(a))

    // flares.js
    private fun outC(v: Float) = 1f - (1f - v).pow(3)
    private fun inOut(v: Float) = if (v < .5f) 2f * v * v else 1f - (-2f * v + 2f).pow(2) / 2f
    /** 打击包络：0→a 线性冲到 1，之后 e^{-k(t-a)} 指数衰减 */
    private fun env(t: Float, k: Float, a: Float = .05f) = if (t < a) t / a else exp(-k * (t - a))
    // frames.js
    private fun pop(t: Float) = 1f - (1f - t).pow(8)       // 头 100ms 撑出大半
    private fun snapF(t: Float) = 1f - (1f - t).pow(14)    // 更陡：给「一闪」的层用
    private fun fadeF(t: Float) = (1f - t).pow(1.4f)
    private fun smooth(v: Float): Float { val x = clamp01(v); return x * x * (3f - 2f * x) }
    // lines.js
    private fun out3(k: Float) = 1f - (1f - clamp01(k)).pow(3)   // ease-out：出画
    private fun in3(k: Float) = clamp01(k).pow(3)                 // ease-in：退去
    // particles.js
    /** 阻力式爆出 0→1：τ 越小前段越陡 */
    private fun burst(t: Float, tau: Float = .07f) = 1f - exp(-t / tau)
    /** 寿命 L 内淡出 (1-t/L)^p，过了寿命返回 0 */
    private fun fadeL(t: Float, L: Float = 1f, p: Float = 1.6f): Float { val k = 1f - t / L; return if (k <= 0f) 0f else k.pow(p) }
    /** 初生白热，T 内回本色 */
    private fun heat(t: Float, T: Float = .25f) = .9f * (1f - min(t / T, 1f))
    /** good 比 perfect 少几颗、小一圈 */
    private fun cnt(hit: Hit, n: Int) = if (hit.perfect) n else (n * .7f).roundToInt()
    private fun sc(hit: Hit) = if (hit.perfect) 1f else .85f
    // rings.js
    private fun snapR(t: Float) = 1f - exp(-t * 18f)               // 0~0.1 弹出六成多
    private fun spread(t: Float) = .72f * snapR(t) + .28f * t      // 弹出后匀速慢漂到 1
    private fun fadeR(t: Float, p: Float = 1.6f) = (1f - clamp01(t)).pow(p)

    private inline fun DrawScope.rotRad(rad: Float, block: DrawScope.() -> Unit) = rotate(rad * RAD, Offset.Zero, block)
    private fun DrawScope.dot(x: Float, y: Float, r: Float, color: Color) = drawCircle(color, r, Offset(x, y))
    private fun DrawScope.ring(r: Float, color: Color, width: Float) = drawCircle(color, r, Offset.Zero, style = Stroke(width))
    private fun DrawScope.arc(r: Float, a0: Float, a1: Float, color: Color, stroke: Stroke) =
        drawArc(color, a0 * RAD, (a1 - a0) * RAD, false, Offset(-r, -r), Size(2f * r, 2f * r), style = stroke)
    /** 软光晕：白热心 → 主色 → 透明。只有快门用，那儿是 lighter 叠加 */
    private fun DrawScope.glow(c: Color, r: Float, a: Float) {
        if (r <= 0f || a <= 0f) return
        drawCircle(
            Brush.radialGradient(0f to mix(c, a, .9f), .35f to mix(c, a * .55f, .3f), 1f to mix(c, 0f), center = Offset.Zero, radius = r),
            r, Offset.Zero, blendMode = BlendMode.Plus,
        )
    }
    /** 正 n 边形路径：外接圆半径 r，第一个顶点在角 rot */
    private fun Path.ngon(n: Int, r: Float, rot: Float): Path {
        reset()
        for (i in 0 until n) {
            val a = rot + i * TAU / n
            if (i == 0) moveTo(r * cos(a), r * sin(a)) else lineTo(r * cos(a), r * sin(a))
        }
        close()
        return this
    }

    // ───────── flares.js ④ 快门：四片叶片内缘围成方口，尾巴朝同一方向甩出（风车式咬合），张开→合拢，整体微微拧一下 ─────────
    private fun DrawScope.shutter(hit: Hit, t: Float, rng: Rng) {
        val u = hit.u; val c = hit.color; val J = if (hit.perfect) 1f else .85f
        val phi = (rng.next() - .5f) * .3f; val dir = if (rng.next() < .5f) 1f else -1f
        val A = u * 1.45f * J; val bt = u * .3f                                          // 最大开口半宽 / 叶片厚
        val open = if (t < .2f) outC(t / .2f) else 1f - inOut(clamp01((t - .28f) / .5f))
        val a = u * .1f + A * open; val e = env(t, 3.6f)
        rotRad(phi + dir * .3f * outC(t)) {
            clipRect(-a, -a, a, a) { glow(c, a * 1.3f, env(t, 7f) * .9f) }              // 开口里透光
            val x0 = -(a + bt + u * .35f); val x1 = a * .2f
            for (i in 0 until 4) rotRad(i * HALF_PI) {
                drawRect(                                                                   // 叶片：内缘亮、往外淡出
                    Brush.linearGradient(0f to mix(c, e * .45f, .5f), 1f to mix(c, e * .06f), start = Offset(0f, -a), end = Offset(0f, -a - bt)),
                    Offset(x0, -a - bt), Size(x1 - x0, bt), blendMode = BlendMode.Plus,
                )
                // 网页宿主两帧之间 lineCap 一直是 round，所以这条内缘线实际是圆头
                drawLine(mix(c, e * .9f, .9f), Offset(x0, -a), Offset(x1, -a), 1.3f, StrokeCap.Round, blendMode = BlendMode.Plus)
            }
        }
    }

    // ───────── frames.js ② 六边框：六边形转着撑开，顶点挂白粒；里面三段细弧反向转 ─────────
    private fun DrawScope.hexFrame(hit: Hit, t: Float, rng: Rng) {
        val u = hit.u; val color = hit.color; val noteH = hit.noteH
        val e = pop(t); val f = fadeF(t); val rot0 = rng.next() * TAU / 6f
        val R = u * (0.5f + 1.9f * e); val rot = rot0 + 0.9f * t
        val p = Path()
        // 中心：小实心六边形一闪即缩 + 白点
        drawPath(p.ngon(6, u * (0.5f - 0.4f * snapF(t)), rot), mix(color, .9f * f, .4f))
        dot(0f, 0f, noteH * .3f, white(.9f * f))
        drawPath(p.ngon(6, R, rot), mix(color, .9f * f), style = Stroke(1.6f, join = StrokeJoin.Round))
        if (hit.perfect) {
            // 顶点白粒：比框淡得快
            val fp = (1f - t).pow(3); val w = white(.95f * fp)
            for (i in 0 until 6) { val a = rot + i * TAU / 6f; dot(R * cos(a), R * sin(a), 1.8f, w) }
        }
        // 内圈三段细弧反向转
        val r2 = R * 0.6f; val a0 = -rot0 - 2.4f * t
        val st = Stroke(1.3f, cap = StrokeCap.Round); val col = mix(color, .7f * f)
        for (i in 0 until 3) { val a = a0 + i * TAU / 3f; arc(r2, a, a + 0.9f, col, st) }
    }

    // ───────── frames.js ④ 三角翻转：外层正三角撑开，内层三角绕横轴翻面变倒三角，翻完长到同大、嵌成六芒星 ─────────
    private fun DrawScope.triFlip(hit: Hit, t: Float, rng: Rng) {
        val u = hit.u; val color = hit.color; val noteH = hit.noteH
        val e = pop(t); val f = fadeF(t); val rot = -HALF_PI + (rng.next() - .5f) * 0.2f + 0.3f * t
        val R = u * (0.6f + 1.9f * e)
        val p = Path()
        // 中心：小实心三角一闪即缩 + 白点
        drawPath(p.ngon(3, u * (0.55f - 0.42f * snapF(t)), rot), mix(color, .9f * f, .4f))
        dot(0f, 0f, noteH * .3f, white(.9f * f))
        // 外层正三角
        p.ngon(3, R, rot)
        if (hit.perfect) drawPath(p, mix(color, .07f * f))
        drawPath(p, mix(color, .9f * f), style = Stroke(1.6f, join = StrokeJoin.Miter))
        // 内层：scaleY 从 1 过 0 到 -1 = 绕横轴翻面，翻到一半是一条横线；翻完慢慢长到和外层一样大
        val flip = cos(PI_F * smooth(t / 0.35f)); val Ri = R * (0.5f + 0.5f * smooth((t - 0.1f) / 0.45f))
        scale(1f, flip, Offset.Zero) {
            p.ngon(3, Ri, rot)
            if (hit.perfect) drawPath(p, mix(color, .07f * f))
            drawPath(p, mix(color, .7f * f), style = Stroke(1.3f, join = StrokeJoin.Miter))
        }
    }

    // ───────── lines.js ⑤ 均衡器：十一根竖细线成排立起、中间高两边矮，随后落下；峰顶小横线像 VU 表慢半拍 ─────────
    private fun DrawScope.equalizer(hit: Hit, t: Float, rng: Rng) {
        // lines.js 的 setup()：主色 + 亮色、淡出曲线、good 小一号、圆头
        val c = hit.color; val hi = mix(c, 1f, .55f); val u = hit.u * (if (hit.perfect) 1f else .85f); val fade = (1f - t).pow(.9f)
        val N = 11; val gap = u * .42f
        for (i in 0 until N) {
            val j = i - (N - 1) / 2f; val x = j * gap; val env = .3f + .7f * cos(j / (N - 1) * PI_F)
            val H = u * 1.9f * env * (.7f + .3f * rng.next()); val rise = out3((t - abs(j) * .012f) / .1f)
            val h = H * rise * (1f - in3((t - .15f) / .85f)); val hc = H * rise * (1f - in3((t - .4f) / .6f))
            if (h < .5f) continue
            drawLine(mix(c, (.45f + .5f * env) * fade), Offset(x, 0f), Offset(x, -h), 1.5f, StrokeCap.Round)
            drawLine(mix(hi, fade), Offset(x - gap * .22f, -hc - 2f), Offset(x + gap * .22f, -hc - 2f), 1.5f, StrokeCap.Round)
        }
    }

    // ───────── particles.js 1 方粒：十来颗小方块弹出，减速漂散、微微翻转后淡出 ─────────
    private fun DrawScope.squareBits(hit: Hit, t: Float, rng: Rng) {
        val u = hit.u; val c = hit.color; val s = sc(hit); val N = cnt(hit, 14)
        // 中心一点白热，0.1 内缩没
        if (t < 0.1f) {
            val k = t / 0.1f
            dot(0f, 0f, u * 0.4f * (1f - 0.7f * k), mix(c, 0.9f * (1f - k), 0.9f))
        }
        val e = burst(t)
        for (i in 0 until N) {
            val ang = (i.toFloat() / N) * TAU + (rng.next() - 0.5f) * 0.6f   // 均匀撒一圈再抖，不扎堆
            val R = u * (0.9f + rng.next() * 1.4f) * s                        // 减速后停在 0.9u~2.3u，远近错开别排成圈
            val sz = u * (0.15f + rng.next() * 0.17f)
            val spin = (rng.next() - 0.5f) * 2.5f
            val L = 0.7f + rng.next() * 0.3f
            val a = fadeL(t, L)
            if (a <= 0f) continue
            val cx = cos(ang); val sy = sin(ang)
            // 阻力减速 + 一点残余外漂 + 轻微下坠
            val x = cx * (R * e + u * 0.25f * t)
            val y = sy * (R * e + u * 0.25f * t) + u * 0.5f * t * t
            val g = sz * (1f + 0.3f * e)                                        // 边飞边略变大
            translate(x, y) { rotRad(spin * e) { drawRect(mix(c, a, heat(t)), Offset(-g / 2f, -g / 2f), Size(g, g)) } }
        }
    }

    // ───────── particles.js 2 火花：火星往上喷成扇面，被重力拽回，拖着短尾落下 ─────────
    private fun DrawScope.sparks(hit: Hit, t: Float, rng: Rng) {
        val u = hit.u; val c = hit.color; val s = sc(hit); val N = cnt(hit, 24)
        // 落点一小片横向白热，很快熄
        if (t < 0.1f) {
            val k = t / 0.1f; val rx = u * 0.7f * (1f - 0.5f * k); val ry = u * 0.18f
            drawOval(mix(c, 0.95f * (1f - k), 0.95f), Offset(-rx, -ry), Size(2f * rx, 2f * ry))
        }
        val G = u * 5.5f                                                        // 重力
        for (i in 0 until N) {
            val ang = -HALF_PI + (rng.next() - 0.5f) * 1.5f                     // 朝上 ±43° 的扇面
            val v = u * (1.8f + rng.next() * 1.2f) * s
            val w = u * (0.06f + rng.next() * 0.07f)
            val L = 0.55f + rng.next() * 0.35f                                  // 最晚 0.9 灭，落到线下一点点就熄
            val a = fadeL(t, L, 1.2f)
            if (a <= 0f) continue
            val cx = cos(ang); val sy = sin(ang)
            // at(tt) = 阻力式射出 + 重力；尾巴 = 4.5% 时长前的位置
            val e1 = burst(t, 0.09f); val x = cx * v * e1; val y = sy * v * e1 + G * t * t
            val t0 = if (t > 0.045f) t - 0.045f else 0f
            val e0 = burst(t0, 0.09f); val x0 = cx * v * e0; val y0 = sy * v * e0 + G * t0 * t0
            drawLine(mix(c, a * 0.25f), Offset(x0, y0), Offset(x, y), w * 3f, StrokeCap.Round)                    // 软晕
            drawLine(mix(c, a, heat(t, 0.35f)), Offset(x0, y0), Offset(x, y), w * (1.2f - 0.6f * t), StrokeCap.Round)
        }
    }

    // ───────── particles.js 3 螺旋尘：光尘顺着两条旋臂转着散开，转速渐停、边散边冷却 ─────────
    private fun DrawScope.spiralDust(hit: Hit, t: Float, rng: Rng) {
        val u = hit.u; val c = hit.color; val s = sc(hit); val N = cnt(hit, 80)
        val e = burst(t, 0.08f)
        for (i in 0 until N) {
            val q = rng.next()                                                   // 在臂上的位置 0(根)..1(梢)
            val R = u * (0.4f + 2.1f * q) * s * (0.85f + rng.next() * 0.3f)
            val ang0 = (i and 1) * PI_F + q * 1.8f + (rng.next() - 0.5f) * 0.7f  // 两臂对开，越外越扭
            val r = u * (0.025f + rng.next() * rng.next() * 0.09f)             // 多数很细，偶有一两颗大的
            val L = 0.55f + rng.next() * 0.45f
            val a = fadeL(t, L, 1.4f)
            if (a <= 0f) continue
            val ang = ang0 + 1.3f * e                                            // 整体再旋 ~75°，跟着减速停
            val rr = R * e + u * 0.2f * t
            val x = cos(ang) * rr; val y = sin(ang) * rr
            dot(x, y, r * 2f + u * 0.03f, mix(c, a * 0.18f, 0.1f))                // 软晕
            dot(x, y, r, mix(c, a, 0.35f + 0.6f * (1f - min(t / 0.3f, 1f))))       // 亮芯，由白热回本色
        }
    }

    // ───────── particles.js 4 聚爆：一圈光点先向中心收拢，攒住一瞬再炸开，越飞越慢 ─────────
    private fun DrawScope.implode(hit: Hit, t: Float, rng: Rng) {
        val u = hit.u; val c = hit.color; val s = sc(hit); val N = cnt(hit, 18)
        val T0 = 0.08f                                                          // 收拢用时
        val tt = (t - T0) / (1f - T0)                                            // 炸开后的进度
        for (i in 0 until N) {
            val ang = (i.toFloat() / N) * TAU + (rng.next() - 0.5f) * 0.3f
            val R0 = u * (0.9f + rng.next() * 0.5f)                              // 起手环
            val R1 = u * (1.8f + rng.next() * 0.7f) * s                          // 炸开落点
            val r = u * (0.07f + rng.next() * 0.07f)
            val L = 0.7f + rng.next() * 0.3f
            val cx = cos(ang); val sy = sin(ang)
            if (t < T0) {
                val k = t / T0; val rr = u * 0.2f + (R0 - u * 0.2f) * (1f - k * k)   // ease-in 收拢到 0.2u
                dot(cx * rr, sy * rr, r, mix(c, 0.5f + 0.5f * k, 0.3f + 0.6f * k))
                continue
            }
            val a = fadeL(tt, L, 1.5f)
            if (a <= 0f) continue
            // 刚炸开拖一小截尾，减速后收成点
            val rr = implodeR(u, R1, tt); val r0 = implodeR(u, R1, if (tt > 0.03f) tt - 0.03f else 0f)
            drawLine(mix(c, a, heat(tt, 0.2f)), Offset(cx * r0, sy * r0), Offset(cx * rr, sy * rr), r * 2f, StrokeCap.Round)
        }
        // 攒到中心那一瞬的闪光：T0 峰值，之后 0.1 内胀开熄掉
        if (t > T0 * 0.5f && t < T0 + 0.1f) {
            val k = if (t < T0) (t - T0 * 0.5f) / (T0 * 0.5f) else 1f - (t - T0) / 0.1f
            dot(0f, 0f, u * 0.35f * (if (t < T0) k else 1f + (1f - k) * 0.8f), mix(c, 0.95f * k, 0.9f))
        }
    }
    /** 聚爆里的 at(k)：炸开后粒子离中心的距离 */
    private fun implodeR(u: Float, R1: Float, k: Float) = u * 0.2f + (R1 - u * 0.2f) * burst(k, 0.06f) + u * 0.2f * k

    // ───────── rings.js ⑥ 碎环：细环一口气撑到最大、临碎前亮一下变细，碎成一圈小点继续往外散、边散边淡 ─────────
    private fun DrawScope.shatterRing(hit: Hit, t: Float, rng: Rng) {
        // rings.js 的 base()：主色、撑满半径（直径 ≈ 4.6u）、总亮度；good 半径收 15%、亮度收 20%
        val c = hit.color; val R = hit.u * 2.3f * (if (hit.perfect) 1f else .85f); val A = if (hit.perfect) 1f else .8f
        val u = hit.u; val N = 28; val TB = .38f
        if (t < TB) {
            val s = t / TB; val flash = clamp01((s - .7f) / .3f)
            ring(R * .92f * spread(t), mix(c, A, .25f + .6f * flash), 2f - s)
        }
        // 碎后：网页把 28 颗的 3 个随机量 P[] 在开头一次算完，这里为了不开数组边取边画 —— rng 的顺序、次数一样
        val rb = R * .92f * spread(TB); val tau = (t - TB) / (1f - TB); val sp = 1f - (1f - tau).pow(2.2f)
        val col = mix(c, A * fadeR(tau, 1.3f), .35f)
        for (i in 0 until N) {
            val a = i * TAU / N + (rng.next() - .5f) * .12f; val v = .4f + .5f * rng.next(); val sz = .8f + .6f * rng.next()
            if (t < TB) continue
            val r = rb + v * u * sp
            dot(cos(a) * r, sin(a) * r, u * .07f * sz * (1f - .5f * tau), col)
        }
        // 线上那一点：小圆，t=0 最亮，0.35 内收掉
        if (t < .35f) dot(0f, 0f, hit.noteH * .4f, mix(c, A * fadeR(t / .35f, 1.2f), .6f))
    }
}
