package app.yxi.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.yxi.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs

/** D-Pad 能送的键。名字是给用户看的，[bytes] 是真正打进 PTY 的。 */
enum class Key(val label: String, val bytes: ByteArray) {
    Up("↑", byteArrayOf(27, '['.code.toByte(), 'A'.code.toByte())),
    Down("↓", byteArrayOf(27, '['.code.toByte(), 'B'.code.toByte())),
    Right("→", byteArrayOf(27, '['.code.toByte(), 'C'.code.toByte())),
    Left("←", byteArrayOf(27, '['.code.toByte(), 'D'.code.toByte())),
    Enter("⏎", byteArrayOf(13)),
    Esc("esc", byteArrayOf(27)),
    Tab("tab", byteArrayOf(9)),
    ShiftTab("⇧tab", byteArrayOf(27, '['.code.toByte(), 'Z'.code.toByte())),
    CtrlC("^C", byteArrayOf(3)),
    CtrlD("^D", byteArrayOf(4)),
    CtrlZ("^Z", byteArrayOf(26)),
    Space("␣", byteArrayOf(32)),
    ;
    companion object {
        /** 两个角能配的键。方向键不在里面 —— 它们在盘上。 */
        val CORNER = listOf(Esc, Tab, ShiftTab, CtrlC, CtrlD, CtrlZ, Space, Enter)
    }
}

/**
 * 方向盘：**四向 + 中央 Enter**，左右上角两个可配置键（长按换）。
 *
 * ⚠️ **它是一个手势，不是五个按钮。** 按下去按方位判方向、**压住不放持续走**、
 * 手指推向别的方向就跟着换 —— 在 Claude Code 的选择器里连按七八下是常事，
 * 五个独立按钮意味着抬手落手七八次，很难受。
 *
 * 长按连发：先等 400ms（避免点一下变成走两格），然后每 110ms 一发。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DPad(modifier: Modifier = Modifier, send: (ByteArray) -> Unit) {
    var topLeft by remember { mutableStateOf(Key.Esc) }
    var topRight by remember { mutableStateOf(Key.Tab) }
    var picking by remember { mutableStateOf<Boolean?>(null) }   // true=左角 false=右角
    // 当前压着的方向。null = 没压。给绘制用
    var held by remember { mutableStateOf<Key?>(null) }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            Modifier.width(184.dp).padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            CornerKey(topLeft, onTap = { send(topLeft.bytes) }, onLong = { picking = true })
            CornerKey(topRight, onTap = { send(topRight.bytes) }, onLong = { picking = false })
        }
        Wheel(held, send) { held = it }
    }

    picking?.let { left ->
        AlertDialog(
            onDismissRequest = { picking = null },
            title = { Text(if (left) "左上角送什么键" else "右上角送什么键") },
            text = {
                Column {
                    Key.CORNER.forEach { k ->
                        Text(
                            k.label,
                            Modifier.fillMaxWidth()
                                .clickable { if (left) topLeft = k else topRight = k; picking = null }
                                .padding(vertical = 11.dp),
                            style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                }
            },
            confirmButton = { TextButton({ picking = null }) { Text("取消") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CornerKey(k: Key, onTap: () -> Unit, onLong: () -> Unit) {
    Surface(
        color = SurfaceContainerHigh, shape = CircleShape,
        modifier = Modifier.size(48.dp).combinedClickable(onClick = onTap, onLongClick = onLong),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                k.label,
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                color = Muted,
            )
        }
    }
}

/**
 * 盘本体。一个 `pointerInput` 通吃：
 * 按下 → 按落点相对圆心的方位定方向（圆心一小圈是 Enter）→ 压住连发 → 拖动实时改方向 → 抬手停。
 */
@Composable
private fun Wheel(held: Key?, send: (ByteArray) -> Unit, onHeld: (Key?) -> Unit) {
    // ⚠️ `pointerInput(Unit)` 只在第一次组合时抓一份 lambda。直接用 `send` 会一直用到那份旧的
    val fire = rememberUpdatedState(send)
    val size = 184.dp
    val px = with(LocalDensity.current) { size.toPx() }
    val deadZone = px * 0.18f      // 圆心这一小圈算「中央键」

    // ⚠️ **这里只管「连发」，第一下由手势直接发。**
    // 一开始把「按下发一次」也放在这个 effect 里，结果**快速单击完全不响应** ——
    // 按下和抬起如果落在同一帧，`held` 已经变回 null，effect 还没来得及跑第一次 send。
    // 长按能用、快点没反应，用起来就像「D-Pad 有时候不灵」。
    LaunchedEffect(held) {
        val k = held ?: return@LaunchedEffect
        delay(400)                          // 点一下不该变成走两格
        while (isActive) { send(k.bytes); delay(110) }
    }

    Box(
        Modifier.size(size)
            .background(SurfaceContainerLow.copy(alpha = 0.92f), CircleShape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val c = Offset(size.toPx() / 2, size.toPx() / 2)
                    val down = awaitFirstDown()
                    var cur = dirOf(down.position - c, deadZone)
                    fire.value(cur.bytes)              // 第一下就在这儿发，不等重组
                    onHeld(cur)
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull() ?: break
                        if (!ch.pressed) break
                        val d = dirOf(ch.position - c, deadZone)
                        // 手指推到另一个方向：立刻走一格，连发的计时也重来
                        if (d != cur) { cur = d; fire.value(d.bytes); onHeld(d) }
                        ch.consume()
                    }
                    onHeld(null)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(SurfaceContainerHigh, radius = size.toPx() * 0.5f, style = Stroke(1.5f))
            drawCircle(SurfaceContainerHigh, radius = deadZone, style = Stroke(1.5f))
        }
        listOf(
            Key.Up to Alignment.TopCenter, Key.Down to Alignment.BottomCenter,
            Key.Left to Alignment.CenterStart, Key.Right to Alignment.CenterEnd,
            Key.Enter to Alignment.Center,
        ).forEach { (k, align) ->
            Box(Modifier.matchParentSize(), contentAlignment = align) {
                Text(
                    k.label,
                    Modifier.padding(14.dp),
                    style = MaterialTheme.typography.titleMedium,
                    // 压着的那个亮起来 —— 推着走的时候要看得见现在在往哪边走
                    color = if (held == k) Copper else Muted,
                )
            }
        }
    }
}

/** 落点方位 → 方向键。圆心附近算 Enter。 */
private fun dirOf(v: Offset, deadZone: Float): Key = when {
    abs(v.x) < deadZone && abs(v.y) < deadZone -> Key.Enter
    abs(v.x) > abs(v.y) -> if (v.x > 0) Key.Right else Key.Left
    else -> if (v.y > 0) Key.Down else Key.Up
}
