package app.yxi.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.yxi.ui.theme.Copper
import app.yxi.ui.theme.Muted

/**
 * 「我的 → 个性化」（老板 2026-09-06：照 QQ 的个性化装扮做，点进去有分类，先做聊天气泡）。
 *
 * 一页三层：分类列表 → 某一类 → 挑选。装扮本来就有一套（[SkinPicker] + [Cosmetics]），这里只是给它一个正门和分类：
 *  · 聊天气泡走 [BubbleShop]（像 QQ 那页：上面两条示例消息实时预览，下面列表点一款就换预览，拥有的才能「使用」）；
 *  · 头像框 / 终端配色 / 快捷语包直接复用 [SkinPicker] 的分栏。
 * 归属仍是服务端说了算（[Cosmetics.owned] 只是渲染缓存），进来先对一次；没对上要说出来。
 */
@Composable
fun PersonalizeScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var sub by remember { mutableStateOf<String?>(null) }   // null = 分类页；bubble / frame / terminal / phrase
    var live by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { live = Cosmetics.refresh(ctx) }
    BackHandler(enabled = sub != null) { sub = null }

    Column(modifier.fillMaxSize()) {
        Text(
            when (sub) {
                null -> t("个性化"); "bubble" -> t("聊天气泡"); "frame" -> t("头像框"); "terminal" -> t("终端配色"); else -> t("快捷语包")
            },
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(18.dp, 14.dp, 18.dp, 6.dp),
        )
        when (sub) {
            null -> Categories(live) { sub = it }
            "bubble" -> BubbleShop(live)
            else -> SkinPicker(live = live, header = false, only = sub)
        }
    }
}

@Composable
private fun Categories(live: Boolean, onPick: (String) -> Unit) {
    val ctx = LocalContext.current
    val owned = Cosmetics.owned(ctx)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!live) Text(
            t("没跟服务端对上 —— 下面按上次同步的结果显示，可能不是最新的。"),
            Modifier.padding(18.dp, 0.dp), style = MaterialTheme.typography.labelSmall, color = Muted,
        )
        Text(t("装扮"), Modifier.padding(24.dp, 6.dp, 24.dp, 0.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
        CategoryRow(t("聊天气泡"), t("当前：%s").format(Skins.bubble(ctx).label)) { onPick("bubble") }
        CategoryRow(t("头像框"), t("当前：%s").format(Skins.frame(ctx).label)) { onPick("frame") }
        CategoryRow(t("终端配色"), t("当前：%s").format(Skins.terminal(ctx).label)) { onPick("terminal") }
        CategoryRow(t("快捷语包"), t("已拥有 %d / %d").format(Skins.PHRASE_PACKS.count { it.id in owned }, Skins.PHRASE_PACKS.size)) { onPick("phrase") }
        Spacer(Modifier.height(4.dp))
        Text(
            t("装扮从祈愿和活动里来；深渊满星的头像框每期一款。"),
            Modifier.padding(24.dp, 0.dp), style = MaterialTheme.typography.labelSmall, color = Muted,
        )
    }
}

@Composable
private fun CategoryRow(title: String, sub: String, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp).clip(RoundedCornerShape(22.dp)).clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(18.dp, 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
            Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.outline)
        }
    }
}

/**
 * 聊天气泡商店（照 QQ 那页）：顶上两条示例消息用**当前预览的那款**实时画（[BubblePreview]，和对话页同一段画法），
 * 下面每款一行，点一行只是换预览；拥有的（或默认）才有「使用」。未拥有的说清从哪来，不给点了没反应的按钮。
 */
@Composable
private fun BubbleShop(live: Boolean) {
    val ctx = LocalContext.current
    val owned = Cosmetics.owned(ctx)
    val current = Cosmetics.picked(ctx, Skins.BUBBLE)
    var previewId by remember { mutableStateOf(current) }
    val preview = Skins.BUBBLES.firstOrNull { it.id == previewId } ?: Skins.BUBBLES[0]
    val previewOwned = preview.id.isEmpty() || preview.id in owned

    Column(Modifier.fillMaxSize()) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth().padding(14.dp, 0.dp, 14.dp, 10.dp),
        ) {
            Column(Modifier.padding(18.dp, 18.dp, 18.dp, 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                BubblePreview(preview)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(preview.label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    when {
                        current == preview.id -> Text(t("使用中"), style = MaterialTheme.typography.labelMedium, color = Copper)
                        previewOwned -> Text(
                            t("使用"),
                            Modifier.clip(RoundedCornerShape(100.dp)).background(MaterialTheme.colorScheme.primary)
                                .clickable { Cosmetics.pick(ctx, Skins.BUBBLE, preview.id) }.padding(18.dp, 8.dp),
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary,
                        )
                        else -> Text(t("未拥有 · 祈愿可得"), style = MaterialTheme.typography.labelMedium, color = Muted)
                    }
                }
            }
        }
        if (!live) Text(
            t("没跟服务端对上 —— 下面按上次同步的结果显示，可能不是最新的。"),
            Modifier.padding(18.dp, 0.dp, 18.dp, 6.dp), style = MaterialTheme.typography.labelSmall, color = Muted,
        )
        LazyColumn(
            Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp, 0.dp, 14.dp, 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(Skins.BUBBLES.size) { i ->
                val b = Skins.BUBBLES[i]
                val has = b.id.isEmpty() || b.id in owned
                val shape = RoundedCornerShape(16.dp)
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow, shape = shape,
                    modifier = Modifier.fillMaxWidth().clip(shape).clickable { previewId = b.id }
                        .then(if (previewId == b.id) Modifier.border(1.5.dp, Copper, shape) else Modifier),
                ) {
                    Row(Modifier.padding(12.dp, 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        // 小样就是真气泡缩小版（同一段画法），描边 / 尾巴 / 角标一个不少
                        Box(Modifier.size(78.dp, 54.dp), contentAlignment = Alignment.Center) {
                            BubbleBox(b, compact = true) { Text("Aa", Modifier.padding(10.dp, 5.dp), style = MaterialTheme.typography.labelMedium, color = bubbleInk(b)) }
                        }
                        Text(b.label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = if (has) MaterialTheme.colorScheme.onSurface else Muted)
                        Text(
                            when { current == b.id -> t("使用中"); !has -> t("未拥有"); else -> "" },
                            style = MaterialTheme.typography.labelSmall, color = if (current == b.id) Copper else Muted,
                        )
                    }
                }
            }
        }
    }
}
