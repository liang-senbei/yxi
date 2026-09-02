package app.yxi.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 会**变形的按钮** —— 抄用户选中的那个 Dribbble 动效（GIF A）：
 * 点一下 → 就地变进度条 → 成功打勾 / 失败「Oops」。
 *
 * 两种用法：
 *  · [SubmitButton]：**自管理**，给它 `work`（真去干活），它自己管状态。发消息/装公钥用。
 *  · [MorphButton]：**纯视觉**，状态从外面传进来。下载更新用 —— 因为下载得挂在
 *    活得比这个界面久的 scope 上（不然切个页面就断了），状态放在外面的 holder 里。
 */
enum class MorphPhase { Idle, Run, Ok, Fail }

private val green = Color(0xFF5FB570)

/**
 * 变形按钮的纯视觉。状态从外面来；空闲/失败态点一下触发 [onTap]。
 *
 * ⚠️ **成功态默认不可点** —— 它通常是「做完了」的收尾，再点没有意义。
 * 但有的成功态**本身就是个动作**（比如「已下好 · 点一下安装」），
 * 那种要把 [okTap] 打开，否则用户照着字面点下去**一点反应都没有**（用户报过）。
 * ⚠️ 收尾话术和可点状态必须一致：**写成动词就得能点，不能点就别写成动词。**
 */
@Composable
fun MorphButton(
    phase: MorphPhase,
    label: String,
    modifier: Modifier = Modifier,
    height: Dp = 54.dp,
    /** Ok/Fail 时圆圈旁边的字（成功提示 / 失败原因）。 */
    msg: String = "",
    /** Run 时的进度：0..1 = 确定进度；<0 = 不确定（转圈）。 */
    progress: Float = -1f,
    /** 成功态也能点吗。见类注释 —— 只有「成功态本身是个动作」时才打开。 */
    okTap: Boolean = false,
    onTap: () -> Unit = {},
) {
    val bg by animateColorAsState(
        when (phase) {
            MorphPhase.Idle -> MaterialTheme.colorScheme.primary
            MorphPhase.Run -> MaterialTheme.colorScheme.surfaceContainerHighest
            MorphPhase.Ok -> green
            MorphPhase.Fail -> MaterialTheme.colorScheme.errorContainer
        }, tween(300), label = "bg",
    )
    Surface(
        color = bg, shape = RoundedCornerShape(100.dp),
        modifier = modifier.height(height)
            .clickable(
                enabled = phase == MorphPhase.Idle || phase == MorphPhase.Fail ||
                    (phase == MorphPhase.Ok && okTap),
                onClick = onTap,
            ),
    ) {
        when (phase) {
            MorphPhase.Idle -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("$label  →", color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            MorphPhase.Run -> Row(
                Modifier.fillMaxSize().padding(8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(height - 16.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.5.dp)
                }
                Box(Modifier.weight(1f).height(7.dp).clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)) {
                    val w by animateFloatAsState(if (progress < 0f) 1f else progress, tween(150), label = "fill")
                    if (progress >= 0f)
                        Box(Modifier.fillMaxWidth(w).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(green))
                    else
                        // ⚠️ 不知道进度时**让它跑**。原来这里画一根**不动的** 40% 条 ——
                        // 圈在转、条却钉死在四成，看着像卡住了，比不画还糟。
                        // 走这条路的有装公钥、发消息、查额度：它们都问不到百分比。
                        // 外面那层 Box 有 clip，滑出去的部分自然被裁掉。
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                            val x by rememberInfiniteTransition(label = "indet").animateFloat(
                                -0.42f, 1f,
                                infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "slide",
                            )
                            Box(
                                Modifier.fillMaxWidth(0.4f).fillMaxHeight().offset(x = maxWidth * x)
                                    .clip(RoundedCornerShape(4.dp)).background(green.copy(alpha = .6f)),
                            )
                        }
                }
                Spacer(Modifier.width(4.dp))
            }
            MorphPhase.Ok, MorphPhase.Fail -> Row(
                Modifier.fillMaxSize().padding(8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val ok = phase == MorphPhase.Ok
                Box(Modifier.size(height - 16.dp).clip(CircleShape)
                    .background(if (ok) Color.Transparent else MaterialTheme.colorScheme.error),
                    contentAlignment = Alignment.Center) {
                    Mark(ok = ok, color = if (ok) Color.White else MaterialTheme.colorScheme.onError)
                }
                Text(
                    if (ok) msg else "$msg · ${t("点重试")}",
                    Modifier.weight(1f),
                    color = if (ok) Color.White else MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 2,
                )
            }
        }
    }
}

/** 自管理版：给它 [work]，它自己管 idle→run→ok/fail。发消息/装公钥用。 */
@Composable
fun SubmitButton(
    label: String,
    modifier: Modifier = Modifier,
    height: Dp = 54.dp,
    successLabel: String = t("完成"),
    onSuccess: () -> Unit = {},
    work: suspend (progress: (Float) -> Unit) -> Result<String>,
) {
    var phase by remember { mutableStateOf(MorphPhase.Idle) }
    var prog by remember { mutableFloatStateOf(-1f) }
    var msg by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    MorphButton(phase, label, modifier, height, msg, prog) {
        phase = MorphPhase.Run; prog = -1f; msg = ""
        scope.launch {
            val r = runCatching { work { p -> prog = p.coerceIn(0f, 1f) } }
                .getOrElse { if (it is CancellationException) throw it; Result.failure(it) }
            r.onSuccess { m -> msg = m.ifBlank { successLabel }; phase = MorphPhase.Ok; delay(950); onSuccess() }
                .onFailure { e -> msg = (e.message ?: t("失败了")).take(40); phase = MorphPhase.Fail }
        }
    }
}

/** 画 ✓ 或 ✕。 */
@Composable
private fun Mark(ok: Boolean, color: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(24.dp)) {
        val s = size.minDimension
        scale(s / 24f, s / 24f, pivot = Offset.Zero) {
            val p = if (ok) "M5 13l4 4 10-11" else "M6 6l12 12M18 6L6 18"
            drawPath(PathParser().parsePathString(p).toPath(), color,
                style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
