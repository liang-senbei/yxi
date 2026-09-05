package app.yxi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.yxi.ui.theme.Copper
import app.yxi.ui.theme.Muted

/**
 * 装扮 —— 我有哪些、当前用哪个。配色表和存取在 [Skins] / [Cosmetics]。
 *
 * 三条规矩（cc-Yxi 2026-09-04 定）：
 *  1. **未拥有的也要看得见**（同卡牌库：让人知道还差什么），但**点不动**。
 *  2. **当前用的那套要有明确标记，点一下就换** —— 换装扮是纯外观、随时可逆，
 *     再弹一个确认框只是碍事（STYLE.md：确认框留给危险动作）。
 *  3. **「默认」永远在列、永远可选** —— 那是退路。撤销、换手机、缓存被清，都靠它兜住。
 *
 * ⚠️ [live] = 这次**真的跟服务端对上了没有**（`Cosmetics.refresh` 的返回值）。
 * 没对上而列表是空的，写「你还没有任何装扮」就是骗人 —— 那时要说的是「没对上，按上次的显示」。
 */
@Composable
fun SkinPicker(live: Boolean = false, header: Boolean = true, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val owned = Cosmetics.owned(ctx)

    // 默认那套不算「收集品」，不进分母 —— 它是退路，不是奖品
    // 深渊框只有拿到的才出现在列表里（每期一款、期末停产，不列「未拥有」的过期款）
    val frames = Skins.frames(ctx)
    val all = Skins.TERMS.count { it.id.isNotEmpty() } +
        Skins.BUBBLES.count { it.id.isNotEmpty() } + Skins.PHRASE_PACKS.size + frames.count { it.id.isNotEmpty() }
    val has = (Skins.TERMS.map { it.id } + Skins.BUBBLES.map { it.id } + Skins.PHRASE_PACKS.map { it.id } + frames.map { it.id })
        .count { it.isNotEmpty() && it in owned }

    Column(modifier.fillMaxSize()) {
        // 嵌在卡牌库里时不画大标题 —— 上面「角色 / 装扮」那个分栏已经说过一遍了
        if (header) Column(Modifier.padding(18.dp, 14.dp, 18.dp, 6.dp)) {
            Text(t("装扮"), style = MaterialTheme.typography.headlineMedium)
            Text(
                t("已拥有 %d / %d").format(has, all),
                style = MaterialTheme.typography.labelMedium, color = Muted,
            )
            if (!live) {
                Spacer(Modifier.height(6.dp))
                Text(
                    // 「拿不到」和「没有」分开说 —— 归属在服务端，这里显示的是上次对上的结果
                    t("没跟服务端对上 —— 下面按上次同步的结果显示，可能不是最新的。"),
                    style = MaterialTheme.typography.labelSmall, color = Muted,
                )
            }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(14.dp, 4.dp, 14.dp, 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!header) item {
                Text(
                    t("已拥有 %d / %d").format(has, all),
                    Modifier.padding(4.dp, 2.dp, 0.dp, 0.dp),
                    style = MaterialTheme.typography.labelMedium, color = Muted,
                )
            }
            if (!header && !live) item {
                Text(
                    t("没跟服务端对上 —— 下面按上次同步的结果显示，可能不是最新的。"),
                    Modifier.padding(4.dp, 6.dp, 0.dp, 0.dp),
                    style = MaterialTheme.typography.labelSmall, color = Muted,
                )
            }
            item { SectionTitle(t("头像框")) }
            items(frames.size) { i ->
                val f = frames[i]
                SkinRow(
                    label = f.label,
                    owned = f.id.isEmpty() || f.id in owned,
                    current = Cosmetics.picked(ctx, Skins.FRAME) == f.id,
                    onPick = { Cosmetics.pick(ctx, Skins.FRAME, f.id) },
                ) { FrameSwatch(f) }
            }

            item { SectionTitle(t("终端配色")) }
            items(Skins.TERMS.size) { i ->
                val s = Skins.TERMS[i]
                SkinRow(
                    label = s.label,
                    owned = s.id.isEmpty() || s.id in owned,
                    current = Cosmetics.picked(ctx, Skins.TERMINAL) == s.id,
                    onPick = { Cosmetics.pick(ctx, Skins.TERMINAL, s.id) },
                ) { TermSwatch(s) }
            }

            item { SectionTitle(t("气泡配色")) }
            items(Skins.BUBBLES.size) { i ->
                val s = Skins.BUBBLES[i]
                SkinRow(
                    label = s.label,
                    owned = s.id.isEmpty() || s.id in owned,
                    current = Cosmetics.picked(ctx, Skins.BUBBLE) == s.id,
                    onPick = { Cosmetics.pick(ctx, Skins.BUBBLE, s.id) },
                ) { BubbleSwatch(s) }
            }

            item { SectionTitle(t("快捷语包")) }
            items(Skins.PHRASE_PACKS.size) { i ->
                val pk = Skins.PHRASE_PACKS[i]
                // ⚠️ 语包**不是二选一，拥有即生效**（都接在常用语后面）。
                //    所以这里没有「当前使用」，也点不动 —— 摆在这儿只为回答「我还差什么」。
                SkinRow(
                    label = pk.label,
                    owned = pk.id in owned,
                    current = false,
                    onPick = null,
                    stateText = if (pk.id in owned) t("已生效") else null,
                ) { PackSwatch(pk) }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text, Modifier.padding(4.dp, 12.dp, 0.dp, 2.dp),
        style = MaterialTheme.typography.labelLarge, color = Muted,
    )
}

/**
 * 一行 = 一套装扮。左边是**真的用那套颜色画的**小样（不是色块图标）——
 * 挑配色只看名字挑不出来。
 */
@Composable
private fun SkinRow(
    label: String,
    owned: Boolean,
    current: Boolean,
    onPick: (() -> Unit)?,
    stateText: String? = null,
    swatch: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = shape,
        modifier = Modifier.fillMaxWidth().clip(shape)
            // 未拥有的**不给点**：点了没反应比点了有反应更难解释，所以干脆不接 clickable
            .then(if (owned && onPick != null) Modifier.clickable(onClick = onPick) else Modifier)
            // 选中的描一圈铜边。别只靠右边那行小字 —— 一眼要看得出用的是哪套
            .then(if (current) Modifier.border(1.5.dp, Copper, shape) else Modifier),
    ) {
        Row(
            Modifier.padding(12.dp, 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ⚠️⚠️ 小样**不压暗、不去饱和**（跟卡牌库相反，这是有意的）。
            //    卡牌库压暗的是**画**，轮廓还在，认得出是哪张；配色小样压暗的是**颜色本身** ——
            //    实测「终端·琥珀」和「终端·青」压到 0.32 之后是同一坨灰，预览就白做了，
            //    而人想要的正是那个颜色。「有没有」右边那行字（未拥有）说得清清楚楚，不靠灰。
            swatch()
            Text(
                label, Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                color = if (owned) MaterialTheme.colorScheme.onSurface else Muted,
            )
            val tail = when {
                stateText != null -> stateText
                !owned -> t("未拥有")
                current -> t("使用中")
                else -> null
            }
            tail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (current) Copper else Muted,
                )
            }
        }
    }
}

/** 终端小样：就是那套配色画的一行提示符。 */
@Composable
private fun TermSwatch(s: Skins.Term) {
    Box(
        Modifier.size(58.dp, 34.dp).clip(RoundedCornerShape(8.dp)).background(s.bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "❯ ls", style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace, color = s.fg,
        )
    }
}

/** 气泡小样：右下角那个尖，跟真气泡同一个形状。 */
@Composable
private fun BubbleSwatch(s: Skins.Bubble) {
    // id 为空 = 跟着主题走，小样也得画主题色，不然「默认」看着像另一套配色
    val bg = if (s.id.isEmpty()) MaterialTheme.colorScheme.primaryContainer else s.bg
    val on = if (s.id.isEmpty()) MaterialTheme.colorScheme.onPrimaryContainer else s.on
    Box(
        Modifier.size(58.dp, 34.dp).clip(RoundedCornerShape(12.dp, 12.dp, 4.dp, 12.dp)).background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Text("Aa", style = MaterialTheme.typography.labelMedium, color = on)
    }
}

/** 语包小样：包里第一句话，比任何图标都说明问题。 */
@Composable
private fun PackSwatch(pk: Skins.Pack) {
    Box(
        Modifier.size(58.dp, 34.dp).clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            pk.lines.first(), Modifier.padding(horizontal = 4.dp),
            style = MaterialTheme.typography.labelSmall, color = Muted, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

/** 头像框小样：一枚灰底小头像 + 框本身，跟「我的」上那圈是同一段绘制代码，静止不转。 */
@Composable
private fun FrameSwatch(f: Skins.Frame) {
    val disc = MaterialTheme.colorScheme.surfaceContainerHigh
    androidx.compose.foundation.Canvas(Modifier.size(58.dp, 34.dp)) {
        val c = androidx.compose.ui.geometry.Offset(size.width / 2, size.height / 2)
        val r = size.height / 2 - 4.dp.toPx()
        drawCircle(disc, radius = r - 2.dp.toPx(), center = c)
        drawAvatarFrame(f, c, r, stroke = 2.dp.toPx(), spin = 0f)
    }
}
